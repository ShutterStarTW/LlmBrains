package com.shutterstar.agenthub.storage

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.shutterstar.agenthub.OsDetector
import com.shutterstar.agenthub.environment.mcp.discovery.JsonArray
import com.shutterstar.agenthub.environment.mcp.discovery.JsonObject
import com.shutterstar.agenthub.environment.mcp.discovery.JsonString
import com.shutterstar.agenthub.environment.mcp.discovery.SafeJsonParser
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillOwnershipEntryState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillOwnershipState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncAuditState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncSettingsState
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import com.shutterstar.agenthub.AgentRuntime

/** Idempotent, per-IDE import. Old state and backup directories are never removed or changed. */
class LegacyStateMigration(
    private val home: AgentHubHome,
    private val configRoot: Path,
    private val systemRoot: Path,
    private val userHome: Path = AgentRuntime.hostHome(),
) {
    fun migrate(): Boolean {
        if (!home.prepare()) return false
        return home.withLock("skill-mutations") {
            home.withLock("migration") {
                check(home.withLock("format") { home.canWriteFormat() }) { "Unsupported AgentHub format" }
                val imported = importedConfigIds()
                val configId = configId()
                if (configId in imported) return@withLock false
                val ownership = legacy("AgentHubSkillOwnership", SkillOwnershipState::class.java)
                val audit = legacy("AgentHubSkillSyncAudit", SkillSyncAuditState::class.java)
                val settings = legacy("AgentHubSkillSyncSettings", SkillSyncSettingsState::class.java)
                require(ownership == null || ownership.schemaVersion == 1)
                require(audit == null || audit.schemaVersion == 1)
                require(settings == null || settings.schemaVersion == 1)
                // Backup copies must finish before imported ownership/history become visible.
                copyBackups()
                val auditStore = SharedXmlStore(home, "state/audit.xml", SkillSyncAuditState::class.java, ::SkillSyncAuditState)
                audit?.let { old -> auditStore.update { shared ->
                    shared.copy(entries = (shared.entries + old.entries).groupBy { it.operationId }
                        .values.map { entries -> entries.maxBy { it.timestampEpochMillis } }
                        .sortedBy { it.timestampEpochMillis }.takeLast(SkillSyncAuditState.MAX_ENTRIES).toMutableList())
                } }
                val operationTimes = (auditStore.snapshot().entries + audit?.entries.orEmpty())
                    .associate { it.operationId to it.timestampEpochMillis }
                val oldTimestamp = runCatching { Files.getLastModifiedTime(configRoot.resolve("options/AgentHubSkillOwnership.xml")).toMillis() }.getOrDefault(0)
                ownership?.let { old ->
                    val incoming = old.entries.map { entry ->
                        entry.copy(recordedAtEpochMillis = entry.recordedAtEpochMillis.takeIf { it > 0 }
                            ?: operationTimes[entry.operationId] ?: oldTimestamp)
                    }
                    SharedXmlStore(home, "state/ownership.xml", SkillOwnershipState::class.java, ::SkillOwnershipState).update { shared ->
                        // Existing shared state wins a timestamp tie, including a newer user's removal/re-record.
                        val removals = (old.removedEntries + shared.removedEntries).groupBy(::ownershipKey)
                            .values.map { entries -> entries.maxBy { it.recordedAtEpochMillis } }
                        val removedAt = removals.associate { ownershipKey(it) to it.recordedAtEpochMillis }
                        shared.copy(removedEntries = removals.toMutableList(),
                            entries = (incoming + shared.entries).filter { entry ->
                                val removed = removedAt[ownershipKey(entry)]
                                removed == null || entry.recordedAtEpochMillis > removed
                            }.groupBy(::ownershipKey)
                            .values.map { entries -> entries.maxWith(compareBy<SkillOwnershipEntryState> { it.recordedAtEpochMillis }.thenBy { entries.indexOf(it) }) }
                            .toMutableList())
                    }
                }
                settings?.let { old ->
                    val store = SharedXmlStore(home, "state/sync-settings.xml", SkillSyncSettingsState::class.java, ::SkillSyncSettingsState)
                    store.update { current -> if (Files.exists(store.path, NOFOLLOW_LINKS)) current else old }
                }
                val updated = (imported + configId).sorted().joinToString(",") { "\"$it\"" }
                home.atomicWrite(home.root!!.resolve("migration.json"), "{\"formatVersion\":1,\"imports\":[$updated]}\n".toByteArray())
                true
            }
        }
    }

    private fun ownershipKey(entry: SkillOwnershipEntryState): List<String> = listOf(
        entry.runtimeId.ifBlank { "host" }, entry.scope.ifBlank { "GLOBAL" },
        SkillInstanceKey.normalizeContextPath(entry.contextPath), entry.skillId, entry.agentId,
    )

    private fun configId(): String {
        val path = OsDetector.pathKey(configRoot.toAbsolutePath().normalize().toString())
        return MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun importedConfigIds(): Set<String> {
        val path = home.root!!.resolve("migration.json")
        home.requireSafePath(path)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return emptySet()
        val text = Files.newInputStream(path, NOFOLLOW_LINKS).use { String(it.readNBytes(1024 * 1024 + 1), Charsets.UTF_8) }
        val root = SafeJsonParser.parse(text) as? JsonObject ?: error("Invalid migration state")
        require(Regex("\"formatVersion\"\\s*:\\s*1\\s*(?=[,}])").findAll(text).count() == 1) {
            "Unsupported migration state"
        }
        val ids = root.fields["imports"] as? JsonArray ?: error("Invalid migration state")
        return ids.values.map { (it as? JsonString)?.value?.takeIf { value -> value.matches(Regex("[0-9a-f]{64}")) }
            ?: error("Invalid migration state") }.toSet()
    }

    private fun <S : Any> legacy(componentName: String, type: Class<S>): S? {
        val path = configRoot.resolve("options/$componentName.xml")
        home.requireSafePath(path)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(32 * 1024 * 1024 + 1) }
        require(bytes.size <= 32 * 1024 * 1024)
        val raw = String(bytes, Charsets.UTF_8)
        require(!Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(raw))
        val text = expandMacros(raw)
        val document = JDOMUtil.load(text.byteInputStream())
        val element = when (document.name) {
            "application" -> document.getChildren("component").single { it.getAttributeValue("name") == componentName }
            "component" -> document.also { require(it.getAttributeValue("name") == componentName) }
            type.simpleName -> document
            else -> error("Unexpected legacy state root")
        }
        return XmlSerializer.deserialize(element, type)
    }

    private fun expandMacros(text: String): String {
        // Only expand attribute values through JDOM so XML escaping remains intact.
        val document = JDOMUtil.load(text.byteInputStream())
        val replacements = mapOf(
            "\$USER_HOME\$" to userHome.toString(), "\$APPLICATION_CONFIG_DIR\$" to configRoot.toString(),
            "\$APP_CONFIG\$" to configRoot.toString(), "\$SYSTEM_DIR\$" to systemRoot.toString(),
        )
        fun visit(element: org.jdom.Element) {
            element.attributes.forEach { attribute -> replacements.forEach { (macro, path) -> attribute.value = attribute.value.replace(macro, path) } }
            element.children.forEach(::visit)
        }
        visit(document)
        return JDOMUtil.writeElement(document)
    }

    private fun copyBackups() {
        val oldRoot = systemRoot.resolve("agenthub/skill-backups")
        home.requireSafePath(oldRoot)
        if (!Files.isDirectory(oldRoot, NOFOLLOW_LINKS)) return
        Files.list(oldRoot).use { operations -> operations.forEach { source ->
            home.requireSafePath(source)
            require(Files.isDirectory(source, NOFOLLOW_LINKS))
            val destination = home.backups().resolve(source.fileName.toString())
            home.requireSafePath(destination)
            if (Files.exists(destination, NOFOLLOW_LINKS)) return@forEach
            val staging = home.backups().resolve(".migration-${UUID.randomUUID()}")
            try {
                var entries = 0
                Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        require(++entries <= 100_000 && !attrs.isSymbolicLink && !attrs.isOther)
                        val target = staging.resolve(source.relativize(dir))
                        Files.createDirectories(target)
                        home.permissions(target, directory = true)
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        require(++entries <= 100_000 && attrs.isRegularFile && !attrs.isSymbolicLink && !attrs.isOther)
                        val target = staging.resolve(source.relativize(file))
                        Files.copy(file, target)
                        home.permissions(target)
                        return FileVisitResult.CONTINUE
                    }
                })
                Files.move(staging, destination, ATOMIC_MOVE)
            } finally {
                // Only this operation's private staging directory; never an existing backup.
                if (Files.exists(staging, NOFOLLOW_LINKS)) {
                    home.requireSafePath(staging)
                    Files.walkFileTree(staging, object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
                        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult { Files.delete(dir); return FileVisitResult.CONTINUE }
                    })
                }
            }
        } }
    }
}
