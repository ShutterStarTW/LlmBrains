package com.shutterstar.agenthub.environment.skills.sync.undo

import com.shutterstar.agenthub.environment.skills.discovery.SkillFingerprint
import com.shutterstar.agenthub.environment.skills.sync.execution.BackupService
import com.shutterstar.agenthub.environment.skills.sync.execution.DirectoryDeleter
import com.shutterstar.agenthub.environment.skills.sync.execution.SkillBackup
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncResult
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStep
import com.shutterstar.agenthub.environment.skills.sync.model.SyncError
import com.shutterstar.agenthub.environment.skills.sync.ownership.InMemorySyncOwnershipStore
import com.shutterstar.agenthub.environment.skills.sync.ownership.SyncOwnershipStore
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Reverses one just-executed [SkillSyncResult] by path. A target is restored from its matching
 * backup or removed only while its post-sync fingerprint still matches the result; later user
 * edits make undo fail safely. Ownership records are returned to their pre-operation state for
 * successfully reversed agents. This is intentionally not a replay of arbitrary audit history,
 * because the audit log does not retain the complete operation journal.
 */
internal class UndoService(
    private val backupService: BackupService = BackupService(),
    private val fingerprint: SkillFingerprint = SkillFingerprint(),
    private val ownershipStore: SyncOwnershipStore = InMemorySyncOwnershipStore(),
) {
    fun undo(result: SkillSyncResult): UndoResult {
        val backupsByPath = result.restorableBackups.associateBy { it.originalPath }
        val expectedFingerprints = result.appliedSteps
            .filter { it.succeeded }
            .mapNotNull { stepResult ->
                (stepResult.step as? SkillSyncStep.VerifyFingerprint)?.let { it.path to it.expectedFingerprint }
            }
            .toMap()
        val restored = mutableListOf<Path>()
        val removed = mutableListOf<Path>()
        val errors = mutableListOf<SyncError>()

        val successfulAgentIds = result.previousManagedTargets.keys
        val touchedTargets = result.appliedSteps
            .filter { it.succeeded }
            .mapNotNull { it.step.reversalTarget() }
            .filter { target ->
                target.step !is SkillSyncStep.RemoveExisting || backupsByPath.containsKey(target.path)
            }
            .filter { successfulAgentIds.isEmpty() || it.agentId in successfulAgentIds }
            .asReversed()
            .distinctBy { it.path }
        val failedAgents = mutableSetOf<String>()
        val reversedAgents = mutableSetOf<String>()

        val key = result.instanceKey ?: result.skillId?.let(SkillInstanceKey::legacy)
        val preflightErrors = validateUndo(result, touchedTargets, backupsByPath, expectedFingerprints, key)
        if (preflightErrors.isNotEmpty()) {
            return UndoResult(result.operationId, emptyList(), emptyList(), preflightErrors)
        }

        for (target in touchedTargets) {
            val path = target.path
            val backup = backupsByPath[path]
            if (backup != null) {
                if (backupService.restore(backup)) {
                    restored.add(path)
                    reversedAgents += target.agentId
                } else {
                    errors.add(SyncError(target.agentId, "Could not restore backup for $path."))
                    failedAgents += target.agentId
                }
            } else {
                runCatching { DirectoryDeleter.deleteRecursively(path) }
                    .onSuccess {
                        removed.add(path)
                        reversedAgents += target.agentId
                    }
                    .onFailure {
                        errors.add(SyncError(target.agentId, "Could not remove $path: ${it.message}"))
                        failedAgents += target.agentId
                    }
            }
        }

        if (key != null) {
            for (agentId in reversedAgents - failedAgents) {
                if (!result.previousManagedTargets.containsKey(agentId)) continue
                val previous = result.previousManagedTargets[agentId]
                if (previous == null) {
                    ownershipStore.remove(key, agentId)
                } else {
                    ownershipStore.record(key, previous)
                }
            }
        }

        return UndoResult(result.operationId, restored, removed, errors)
    }

    private fun validateUndo(
        result: SkillSyncResult,
        targets: List<ReversalTarget>,
        backupsByPath: Map<Path, SkillBackup>,
        expectedFingerprints: Map<Path, String>,
        key: SkillInstanceKey?,
    ): List<SyncError> {
        val errors = mutableListOf<SyncError>()
        result.requiredBackupPaths
            .filterNot(backupsByPath::containsKey)
            .forEach { path ->
                errors += SyncError(null, "Required backup for $path is no longer available; undo was not applied.")
            }
        for (target in targets) {
            val expectedFingerprint = expectedFingerprints[target.path]
            val changed = if (expectedFingerprint != null) {
                fingerprint.calculate(target.path) != expectedFingerprint
            } else {
                Files.exists(target.path, LinkOption.NOFOLLOW_LINKS)
            }
            if (changed) {
                errors += SyncError(target.agentId, "${target.path} changed after synchronization; undo was not applied.")
            }

            val current = key?.let { ownershipStore.managedTarget(it, target.agentId) }
            if (current != null && current.operationId != result.operationId) {
                errors += SyncError(target.agentId, "${target.path} is managed by a newer operation; undo was not applied.")
            }
        }

        val canonical = result.canonicalPath
        val removesCanonical = canonical != null && targets.any { it.path == canonical && backupsByPath[canonical] == null }
        if (removesCanonical && key != null) {
            val dependent = ownershipStore.managedTargetsFor(key)
                .firstOrNull { it.operationId != result.operationId }
            if (dependent != null) {
                errors += SyncError(
                    dependent.agentId,
                    "$canonical is used by a newer managed target; undo was not applied.",
                )
            }
        }
        return errors.distinct()
    }

    private fun SkillSyncStep.reversalTarget(): ReversalTarget? = when (this) {
        is SkillSyncStep.CreateLink -> ReversalTarget(agentId, target, this)
        is SkillSyncStep.CopySkill -> ReversalTarget(agentId, target, this)
        is SkillSyncStep.RemoveExisting -> ReversalTarget(agentId, path, this)
        else -> null
    }

    private data class ReversalTarget(
        val agentId: String,
        val path: Path,
        val step: SkillSyncStep,
    )
}
