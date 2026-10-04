package com.shutterstar.agenthub.storage

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.TimeUnit

/** XML state with cross-process read/modify/write locking and atomic replacement. */
class SharedXmlStore<S : Any>(
    private val home: AgentHubHome,
    val relativePath: String,
    private val stateClass: Class<S>,
    private val factory: () -> S,
    private val schemaVersion: Int = currentSchemaVersion(factory()),
    private val statIntervalMillis: Long = 500,
    private val lockTimeoutMillis: Long = 3000,
) : StateStore<S> {
    override val persistent: Boolean get() = true
    val path: Path? = home.root?.resolve(relativePath)?.normalize()
    private val lockName = relativePath.replace('/', '-').replace('\\', '-')
    private var cached = factory()
    private var loadedStamp: String? = null
    private var checkedAt = Long.MIN_VALUE
    private var generation = 0L
    private var newerVersion = false
    private var readFailed = false

    init {
        require(!Path.of(relativePath).isAbsolute && !relativePath.split('/', '\\').contains(".."))
        require(path == null || path.startsWith(home.root!!.normalize()))
    }

    @Synchronized override fun snapshot(): S {
        refresh()
        return copy(cached)
    }

    /** Reloads [cached] when the file changed on disk; cheap enough for stamp polling (no state copy). */
    private fun refresh() {
        if (path == null || home.memoryOnly) return
        val now = System.nanoTime()
        if (loadedStamp == null || checkedAt == Long.MIN_VALUE || now - checkedAt >= TimeUnit.MILLISECONDS.toNanos(statIntervalMillis)) {
            checkedAt = now
            val currentStamp = diskStamp()
            if (loadedStamp != currentStamp) {
                try {
                    cached = read(recoverCorrupt = false)
                    loadedStamp = currentStamp
                    readFailed = false
                } catch (_: CorruptState) {
                    try {
                        if (home.prepare()) {
                            cached = home.withLock(lockName, lockTimeoutMillis) { read(recoverCorrupt = true) }
                            loadedStamp = diskStamp()
                            readFailed = false
                        }
                    } catch (_: Exception) {
                        readFailed = true
                        home.warning("AgentHub could not read its data; the previous in-memory snapshot is being shown.")
                    }
                } catch (_: Exception) {
                    readFailed = true
                    home.warning("AgentHub could not read its data; the previous in-memory snapshot is being shown.")
                }
            }
        }
    }

    @Synchronized override fun checkWritable() {
        if (path == null || !home.prepare()) return
        home.withLock(lockName, lockTimeoutMillis) {
            if (!home.withLock("format", lockTimeoutMillis) { home.canWriteFormat() }) {
                throw SharedStorageException("AgentHub data uses an unsupported format. Update AgentHub before changing it.")
            }
            read(recoverCorrupt = true)
            if (newerVersion) throw SharedStorageException("AgentHub data uses a newer schema. Update AgentHub before changing it.")
        }
    }

    @Synchronized override fun update(transform: (S) -> S): S {
        if (path == null || !home.prepare()) {
            cached = transform(copy(cached))
            generation++
            return copy(cached)
        }
        return try {
            home.withLock(lockName, lockTimeoutMillis) {
                if (!home.withLock("format", lockTimeoutMillis) { home.canWriteFormat() }) {
                    throw SharedStorageException("AgentHub data was created by a newer or unsupported plugin. Update AgentHub before changing it.")
                }
                val latest = read(recoverCorrupt = true)
                if (newerVersion) throw SharedStorageException("AgentHub data uses a newer schema. Update AgentHub before changing it.")
                val updated = transform(copy(latest))
                val xml = XmlSerializer.serialize(updated).apply { setAttribute("version", schemaVersion.toString()) }
                home.atomicWrite(path, JDOMUtil.writeElement(xml).toByteArray(Charsets.UTF_8))
                cached = updated
                loadedStamp = diskStamp()
                checkedAt = System.nanoTime()
                readFailed = false
                generation++
                copy(cached)
            }
        } catch (_: IOException) {
            // A failed durable transaction must not look successful to a mutation caller.
            home.warning("AgentHub could not save shared data. The operation failed; retry after checking the data location.")
            throw SharedStorageException("AgentHub could not save its shared data. Check the data location and retry.")
        }
    }

    @Synchronized override fun stamp(): String {
        refresh()
        return if (path == null || home.memoryOnly) "memory:$generation" else "${loadedStamp.orEmpty()}:$readFailed"
    }

    /** Must be called under the store lock whenever quarantine is permitted. */
    private fun read(recoverCorrupt: Boolean): S {
        newerVersion = false
        home.requireSafePath(path!!)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return factory()
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAX_XML_BYTES + 1) }
        val parsed = try {
            if (bytes.size > MAX_XML_BYTES) throw CorruptState()
            val text = String(bytes, Charsets.UTF_8)
            // Do not permit external entities/DTDs from shared or migrated files.
            if (DTD_PATTERN.containsMatchIn(text)) throw CorruptState()
            JDOMUtil.load(bytes.inputStream())
        } catch (_: Exception) {
            if (!recoverCorrupt) throw CorruptState()
            quarantine()
            return factory()
        }
        return try {
            val version = parsed.getAttributeValue("version")?.toIntOrNull() ?: schemaVersion
            val stateVersion = parsed.getChildren("option").firstOrNull { it.getAttributeValue("name") == "schemaVersion" }
                ?.getAttributeValue("value")?.toIntOrNull() ?: schemaVersion
            newerVersion = version > schemaVersion || stateVersion > schemaVersion
            if (newerVersion) home.warning("AgentHub data uses a newer schema; this plugin will not overwrite it.")
            require(parsed.name == stateClass.simpleName)
            XmlSerializer.deserialize(parsed, stateClass)
        } catch (_: Exception) {
            if (!recoverCorrupt) throw CorruptState()
            quarantine()
            factory()
        }
    }

    private fun quarantine() {
        // Preserve unknown-format data even if this older serializer cannot interpret it.
        if (newerVersion || !home.withLock("format", lockTimeoutMillis) { home.canWriteFormat() }) throw SharedStorageException("AgentHub data cannot be changed by this plugin version.")
        val target = path!!.resolveSibling("${path.fileName}.corrupt-${System.currentTimeMillis()}-${UUID.randomUUID()}")
        Files.move(path, target, ATOMIC_MOVE)
        home.warning("AgentHub found damaged state, preserved the original in a .corrupt file, and started an empty index.")
    }

    private fun diskStamp(): String = try {
        home.requireSafePath(path!!)
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        "${attributes.lastModifiedTime()}:${attributes.size()}:${attributes.fileKey()}"
    } catch (_: NoSuchFileException) { "missing" }
    catch (_: Exception) { "unavailable" }

    private fun copy(state: S): S = XmlSerializer.deserialize(XmlSerializer.serialize(state), stateClass)
    private class CorruptState : RuntimeException()

    companion object {
        private const val MAX_XML_BYTES = 32 * 1024 * 1024
        private val DTD_PATTERN = Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE)

        private fun currentSchemaVersion(state: Any): Int =
            (state.javaClass.methods.firstOrNull { it.name == "getSchemaVersion" && it.parameterCount == 0 }
                ?.invoke(state) as? Int) ?: 1
    }
}
