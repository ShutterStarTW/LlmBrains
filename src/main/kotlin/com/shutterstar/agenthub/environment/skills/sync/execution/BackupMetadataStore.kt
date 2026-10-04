package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.Properties

/** One backup as recorded on disk, independent of the in-memory [SkillSyncResult][com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncResult] that created it. */
internal data class StoredBackupRecord(
    val instanceKey: SkillInstanceKey,
    val agentId: String,
    val backup: SkillBackup,
)

/**
 * A small text sidecar written next to each [SkillBackup] directory. [BackupService] only ever
 * hands its caller an in-memory [SkillBackup] (no [SkillInstanceKey], no durable record on disk),
 * which is enough for the same-session [com.shutterstar.agenthub.environment.skills.sync.undo.UndoService]
 * but not for Restore Backup or a retention sweep after an IDE restart — this makes every backup
 * self-describing on disk, purely with the JDK (no new persistence dependency), matching
 * [BackupService]'s own "pure JDK, unit-testable with `@TempDir`" discipline.
 */
internal object BackupMetadataStore {
    private const val MAX_SCAN_DEPTH = 3

    fun write(backup: SkillBackup, instanceKey: SkillInstanceKey, agentId: String) {
        val properties = Properties()
        properties.setProperty(KEY_RUNTIME_ID, instanceKey.runtimeId)
        properties.setProperty(KEY_SCOPE, instanceKey.scope.name)
        properties.setProperty(KEY_CONTEXT_PATH, instanceKey.contextPath)
        properties.setProperty(KEY_SKILL_ID, instanceKey.skillId)
        properties.setProperty(KEY_AGENT_ID, agentId)
        properties.setProperty(KEY_OPERATION_ID, backup.operationId)
        properties.setProperty(KEY_ORIGINAL_PATH, backup.originalPath.toString())
        properties.setProperty(KEY_REPRESENTATION, backup.representation.name)
        backup.linkTarget?.let { properties.setProperty(KEY_LINK_TARGET, it.toString()) }
        properties.setProperty(KEY_CREATED_AT, backup.createdAt.toEpochMilli().toString())

        val sidecarPath = sidecarPathFor(backup.backupPath)
        runCatching {
            Files.createDirectories(sidecarPath.parent)
            Files.newBufferedWriter(sidecarPath).use { writer -> properties.store(writer, null) }
        }
    }

    fun sidecarPathFor(backupPath: Path): Path =
        backupPath.resolveSibling("${backupPath.fileName}.meta")

    /** Bounded, best-effort scan of `backupRoot/operationId/agentId/skillId.meta`. Malformed or unreadable sidecars are skipped, never thrown. */
    fun listBackups(backupRoot: Path): List<StoredBackupRecord> {
        if (!Files.isDirectory(backupRoot)) return emptyList()
        val root = backupRoot.toAbsolutePath().normalize()
        val rootAttributes = runCatching {
            Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return emptyList()
        if (rootAttributes.isOther || rootAttributes.isSymbolicLink) return emptyList()

        val sidecars = mutableListOf<Path>()
        runCatching {
            Files.walkFileTree(
                root,
                emptySet(),
                MAX_SCAN_DEPTH,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult =
                        if (directory != root && (attributes.isOther || attributes.isSymbolicLink)) {
                            FileVisitResult.SKIP_SUBTREE
                        } else {
                            FileVisitResult.CONTINUE
                        }

                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        if (!attributes.isOther &&
                            !attributes.isSymbolicLink &&
                            file.toAbsolutePath().normalize().startsWith(root) &&
                            file.fileName.toString().endsWith(".meta")
                        ) {
                            sidecars.add(file)
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }.onFailure { return emptyList() }
        return sidecars.mapNotNull(::read)
    }

    private fun read(sidecarPath: Path): StoredBackupRecord? {
        val properties = Properties()
        runCatching {
            Files.newBufferedReader(sidecarPath).use { reader -> properties.load(reader) }
        }.onFailure { return null }

        val runtimeId = properties.getProperty(KEY_RUNTIME_ID) ?: return null
        val scope = runCatching { SkillScope.valueOf(properties.getProperty(KEY_SCOPE)) }.getOrNull() ?: return null
        val contextPath = properties.getProperty(KEY_CONTEXT_PATH) ?: return null
        val skillId = properties.getProperty(KEY_SKILL_ID) ?: return null
        val agentId = properties.getProperty(KEY_AGENT_ID) ?: return null
        val operationId = properties.getProperty(KEY_OPERATION_ID) ?: return null
        val originalPath = properties.getProperty(KEY_ORIGINAL_PATH)?.let(Path::of) ?: return null
        val representation = runCatching { EffectiveSyncMode.valueOf(properties.getProperty(KEY_REPRESENTATION)) }.getOrNull() ?: return null
        val linkTarget = properties.getProperty(KEY_LINK_TARGET)?.let(Path::of)
        val createdAtEpochMilli = properties.getProperty(KEY_CREATED_AT)?.toLongOrNull() ?: return null
        val backupPath = sidecarPath.resolveSibling(sidecarPath.fileName.toString().removeSuffix(".meta"))
        if (!Files.exists(backupPath, LinkOption.NOFOLLOW_LINKS)) return null

        return StoredBackupRecord(
            instanceKey = SkillInstanceKey(runtimeId, scope, contextPath, skillId),
            agentId = agentId,
            backup = SkillBackup(
                id = "$operationId:$agentId",
                originalPath = originalPath,
                backupPath = backupPath,
                createdAt = Instant.ofEpochMilli(createdAtEpochMilli),
                operationId = operationId,
                representation = representation,
                linkTarget = linkTarget,
            ),
        )
    }

    private const val KEY_RUNTIME_ID = "runtimeId"
    private const val KEY_SCOPE = "scope"
    private const val KEY_CONTEXT_PATH = "contextPath"
    private const val KEY_SKILL_ID = "skillId"
    private const val KEY_AGENT_ID = "agentId"
    private const val KEY_OPERATION_ID = "operationId"
    private const val KEY_ORIGINAL_PATH = "originalPath"
    private const val KEY_REPRESENTATION = "representation"
    private const val KEY_LINK_TARGET = "linkTarget"
    private const val KEY_CREATED_AT = "createdAtEpochMilli"
}
