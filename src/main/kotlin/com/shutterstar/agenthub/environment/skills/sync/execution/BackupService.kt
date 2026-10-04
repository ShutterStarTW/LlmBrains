package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.environment.skills.sync.link.CopyStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.FileLinkStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.LinkResult
import com.shutterstar.agenthub.environment.skills.sync.link.UnixSymlinkStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.WindowsJunctionStrategy
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant

/**
 * Pure JDK, unit-testable with `@TempDir` — `backupRoot` is always caller-supplied rather than
 * resolved via IntelliJ Platform APIs, so the project-local-vs-global-app-storage policy (spec §20)
 * stays a future UI-layer concern, not baked in here.
 */
class BackupService(
    private val copyStrategy: FileLinkStrategy = CopyStrategy(),
    private val restoreCopyStrategy: FileLinkStrategy = copyStrategy,
    private val linkStrategies: Map<EffectiveSyncMode, FileLinkStrategy> = mapOf(
        EffectiveSyncMode.SYMLINK to UnixSymlinkStrategy(),
        EffectiveSyncMode.JUNCTION to WindowsJunctionStrategy(),
    ),
) {
    fun backup(
        agentId: String,
        skillId: String,
        path: Path,
        backupRoot: Path,
        operationId: String,
        representation: EffectiveSyncMode? = null,
        linkTarget: Path? = null,
        instanceKey: SkillInstanceKey = SkillInstanceKey.legacy(skillId),
    ): SkillBackup? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null

        val backupPath = backupRoot.resolve(operationId).resolve(agentId).resolve(skillId)
        val detectedRepresentation = representation ?: if (Files.isSymbolicLink(path)) {
            EffectiveSyncMode.SYMLINK
        } else {
            EffectiveSyncMode.COPY
        }
        val detectedLinkTarget = linkTarget ?: if (detectedRepresentation == EffectiveSyncMode.SYMLINK) {
            runCatching { Files.readSymbolicLink(path) }.getOrNull()
        } else {
            null
        }

        if (detectedRepresentation == EffectiveSyncMode.COPY) {
            val result = copyStrategy.createLink(path, backupPath)
            if (result !is LinkResult.Success) return null
        } else {
            if (detectedLinkTarget == null) return null
            runCatching { Files.createDirectories(backupPath) }.getOrElse { return null }
        }

        val skillBackup = SkillBackup(
            id = "$operationId:$agentId",
            originalPath = path,
            backupPath = backupPath,
            createdAt = Instant.now(),
            operationId = operationId,
            representation = detectedRepresentation,
            linkTarget = detectedLinkTarget,
        )
        BackupMetadataStore.write(skillBackup, instanceKey, agentId)
        return skillBackup
    }

    fun restore(backup: SkillBackup): Boolean {
        if (!Files.exists(backup.backupPath, LinkOption.NOFOLLOW_LINKS)) return false

        val original = backup.originalPath
        val strategy = if (backup.representation == EffectiveSyncMode.COPY) {
            restoreCopyStrategy
        } else {
            backup.linkTarget ?: return false
            linkStrategies[backup.representation] ?: return false
        }

        val result = AtomicPathReplace.replace(original) { staging ->
            if (backup.representation == EffectiveSyncMode.COPY) {
                strategy.createLink(backup.backupPath, staging)
            } else {
                strategy.createLink(requireNotNull(backup.linkTarget), staging)
            }
        }
        return result is LinkResult.Success
    }
}
