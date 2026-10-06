package com.shutterstar.agenthub.storage

import com.shutterstar.agenthub.environment.mcp.discovery.JsonObject
import com.shutterstar.agenthub.environment.mcp.discovery.SafeJsonParser
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import com.shutterstar.agenthub.AgentRuntime

/** A test-injectable user-level home. No filesystem mutation until [prepare] or a store update. */
class AgentHubHome(
    val root: Path? = resolvePath(),
    private val warn: (String) -> Unit = { LOG.warning(it) },
) {
    private val warned = AtomicBoolean()
    @Volatile private var prepared = false
    @Volatile var memoryOnly: Boolean = root == null
        private set

    fun prepare(): Boolean {
        if (memoryOnly) {
            warning("AgentHub data location is unavailable; changes are kept in memory for this IDE session.")
            return false
        }
        if (prepared) return true
        return try {
            listOf("", "state", "backups", "cache", "locks").forEach { directory ->
                val path = root!!.resolve(directory)
                requireSafePath(path)
                Files.createDirectories(path)
                permissions(path, directory = true)
            }
            val probe = Files.createTempFile(root, ".write-probe-", ".tmp")
            Files.delete(probe)
            prepared = true
            true
        } catch (_: Exception) {
            memoryOnly = true
            warning("AgentHub data location is unavailable; changes are kept in memory for this IDE session.")
            false
        }
    }

    fun backups(): Path = root?.resolve("backups") ?: disabledBackupRoot

    fun cachePath(runtimeId: String, name: String): String = "cache/${runtimeDirectory(runtimeId)}/$name"

    fun warning(message: String) {
        if (warned.compareAndSet(false, true)) warn(message)
    }

    fun <T> withLock(name: String, timeoutMillis: Long = 3000, action: () -> T): T {
        check(prepare()) { "AgentHub data location is unavailable" }
        require(name.matches(Regex("[a-zA-Z0-9._-]+")))
        val lockPath = root!!.resolve("locks/$name.lock")
        requireSafePath(lockPath)
        FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            permissions(lockPath)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (true) {
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock != null) return lock.use { action() }
                if (System.nanoTime() >= deadline) throw SharedStorageException("AgentHub data is busy in another IDE. Retry the operation.")
                try { Thread.sleep(20) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw SharedStorageException("AgentHub data operation was interrupted. Retry the operation.")
                }
            }
        }
    }

    /** Called under a lock. Unknown/newer formats are never overwritten. */
    internal fun canWriteFormat(): Boolean {
        val path = root!!.resolve("format.json")
        requireSafePath(path)
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            atomicWrite(path, "{\"formatVersion\":1,\"minimumReaderVersion\":1}\n".toByteArray())
            return true
        }
        val value = Files.newInputStream(path, NOFOLLOW_LINKS).use { String(it.readNBytes(4097), Charsets.UTF_8) }
        fun versionField(name: String): Int? {
            val matches = Regex("\"$name\"\\s*:\\s*(\\d+)\\s*(?=[,}])").findAll(value).toList()
            return matches.singleOrNull()?.groupValues?.get(1)?.toIntOrNull()
        }
        val version = versionField("formatVersion")
        val minimumReader = versionField("minimumReaderVersion")
        val supported = value.toByteArray().size <= 4096 && SafeJsonParser.parse(value) is JsonObject &&
            version == 1 && minimumReader == 1
        if (!supported) warning("AgentHub data has an unsupported format; this plugin will not overwrite it.")
        return supported
    }

    internal fun atomicWrite(path: Path, bytes: ByteArray) {
        requireSafePath(path)
        require(path.toAbsolutePath().normalize().startsWith(root!!.toAbsolutePath().normalize()))
        Files.createDirectories(path.parent)
        permissions(path.parent, directory = true)
        val temporary = path.resolveSibling(".${path.fileName}.tmp-${UUID.randomUUID()}")
        try {
            FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                permissions(temporary)
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            var lastError: IOException? = null
            repeat(5) { attempt ->
                try {
                    Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
                    permissions(path)
                    return
                } catch (error: IOException) {
                    lastError = error
                    if (attempt < 4) {
                        try { Thread.sleep(25) } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw SharedStorageException("AgentHub data operation was interrupted.")
                        }
                    }
                }
            }
            throw lastError ?: IOException("Atomic replacement failed")
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    internal fun requireSafePath(path: Path) {
        generateSequence(path.toAbsolutePath().normalize()) { it.parent }.forEach {
            if (Files.exists(it, NOFOLLOW_LINKS)) {
                val attributes = Files.readAttributes(it, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(!attributes.isSymbolicLink && !attributes.isOther) { "Linked AgentHub data locations are not supported" }
            }
        }
    }

    internal fun permissions(path: Path, directory: Boolean = false) {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
        }
    }

    companion object {
        private val LOG = Logger.getLogger(AgentHubHome::class.java.name)
        private val disabledBackupRoot = Path.of(System.getProperty("java.io.tmpdir"), "agenthub-unavailable-${UUID.randomUUID()}")

        fun resolvePath(
            userHome: Path = AgentRuntime.userHome(),
            environmentValue: String? = System.getenv("AGENTHUB_HOME"),
            propertyValue: String? = System.getProperty("agenthub.home"),
        ): Path? = runCatching {
            val value = propertyValue?.trim()?.takeIf(String::isNotEmpty)
                ?: environmentValue?.trim()?.takeIf(String::isNotEmpty)
            when {
                value == null -> userHome.resolve(".agenthub")
                value == "~" -> userHome
                value.startsWith("~/") || value.startsWith("~\\") -> userHome.resolve(value.substring(2))
                else -> Path.of(value)
            }.toAbsolutePath().normalize()
        }.getOrNull()

        /** Hash suffix prevents distro names that normalize alike from sharing a cache. */
        fun runtimeDirectory(runtimeId: String): String {
            if (runtimeId == "host") return "host"
            val safe = runtimeId.replace(Regex("[^a-zA-Z0-9_-]"), "-").take(64)
            val hash = MessageDigest.getInstance("SHA-256").digest(runtimeId.toByteArray())
                .take(6).joinToString("") { "%02x".format(it) }
            return "$safe-$hash"
        }
    }
}
