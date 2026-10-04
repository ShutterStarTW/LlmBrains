package com.shutterstar.agenthub.environment.skills.sync

import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAction
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAuditEntry
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAuditTrail
import com.shutterstar.agenthub.environment.skills.sync.execution.BackupMetadataStore
import com.shutterstar.agenthub.environment.skills.sync.execution.BackupService
import com.shutterstar.agenthub.environment.skills.sync.execution.OperationJournalStore
import com.shutterstar.agenthub.environment.skills.sync.execution.PersistedOperationJournal
import com.shutterstar.agenthub.environment.skills.sync.execution.PersistedReversalStep
import com.shutterstar.agenthub.environment.skills.sync.execution.PersistedStepKind
import com.shutterstar.agenthub.environment.skills.sync.execution.SkillBackup
import com.shutterstar.agenthub.environment.skills.sync.execution.SkillSyncExecutor
import com.shutterstar.agenthub.environment.skills.sync.execution.StoredBackupRecord
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncResult
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStep
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStepResult
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTargetResult
import com.shutterstar.agenthub.environment.skills.sync.model.SyncError
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SyncTargetOutcome
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.environment.skills.sync.ownership.SyncOwnershipStore
import com.shutterstar.agenthub.environment.skills.sync.planning.CanonicalSkillResolver
import com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillTargetObserver
import com.shutterstar.agenthub.environment.skills.discovery.SkillFingerprint
import com.shutterstar.agenthub.environment.skills.sync.undo.UndoPreview
import com.shutterstar.agenthub.environment.skills.sync.undo.UndoResult
import com.shutterstar.agenthub.environment.skills.sync.undo.UndoService
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant

/**
 * Executes an approved [SkillSyncPlanResult], and owns everything read after the fact: audit
 * history, backup listing/restore, live target status, and undo (both the immediate in-memory
 * path and the disk-persisted [OperationJournalStore] path that survives an IDE restart). Planning
 * lives in [SkillSyncRequestPlanner]; the two share [ownershipStore]/[runtimeId] via
 * [resolveInstanceKey] so a plan built there resolves to the same
 * [com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey] here.
 */
internal class SkillSyncOperationRunner(
    private val executor: SkillSyncExecutor,
    private val auditTrail: SyncAuditTrail,
    private val undoService: UndoService,
    private val ownershipStore: SyncOwnershipStore,
    private val canonicalResolver: CanonicalSkillResolver,
    private val observer: SkillTargetObserver,
    private val fingerprint: SkillFingerprint,
    private val backupService: BackupService,
    private val now: () -> Instant,
    private val runtimeId: String,
) {
    /**
     * Read-only hint for UI action gating: which installed agents currently have an
     * AgentHub-managed target for this skill's canonical instance. The authoritative check still
     * happens inside [SkillSyncRequestPlanner.plan] at request time (ownership verified against
     * live filesystem state) — this only decides which actions are worth offering, never whether
     * they actually apply.
     */
    fun managedTargetIds(skill: AgentSkill, scope: SkillScope, project: DiscoveredProject?): Set<String> {
        val canonical = canonicalResolver.resolve(skill)?.takeIf { it.scope == scope } ?: return emptySet()
        val key = instanceKey(canonical.skillId, scope, project, canonical.canonicalPath)
        return ownershipStore.managedTargetsFor(key).map(ManagedTarget::agentId).toSet()
    }

    /** Read-only: the persisted, most-recent-first audit log for this skill instance. */
    fun historyFor(skill: AgentSkill, scope: SkillScope, project: DiscoveredProject?, limit: Int = 50): List<SyncAuditEntry> {
        val canonical = canonicalResolver.resolve(skill)?.takeIf { it.scope == scope } ?: return emptyList()
        val key = instanceKey(canonical.skillId, scope, project, canonical.canonicalPath)
        return auditTrail.entriesFor(key).take(limit)
    }

    fun execute(
        result: SkillSyncPlanResult,
        targetsByAgentId: Map<String, SkillSyncTarget>,
        scope: SkillScope,
        project: DiscoveredProject?,
        backupRoot: Path,
    ): SkillSyncResult {
        if (scope != result.scope) {
            return SkillSyncResult(
                operationId = result.plan.operationId,
                status = SyncOperationStatus.FAILED,
                appliedSteps = emptyList(),
                errors = listOf(SyncError(null, "Execution scope differs from the reviewed plan.")),
                rollbackAvailable = false,
                skillId = result.planningRequest.skillId,
                instanceKey = result.planningRequest.instanceKey,
                canonicalPath = result.planningRequest.canonicalPath,
            )
        }
        val syncResult = executor.execute(
            result.plan,
            result.planningRequest,
            targetsByAgentId,
            scope,
            project,
            backupRoot,
        )
        auditTrail.record(
            SyncAuditEntry(
                operationId = result.plan.operationId,
                timestamp = now(),
                skillId = result.planningRequest.skillId,
                action = result.action,
                affectedAgents = result.planningRequest.targets.map { it.agentId }.toSet(),
                result = syncResult.status,
                instanceKey = result.planningRequest.instanceKey,
            ),
        )
        return syncResult
    }

    /** Read-only: every backup on disk for this skill instance, most recent first. */
    fun restorableBackups(
        skill: AgentSkill,
        scope: SkillScope,
        project: DiscoveredProject?,
        backupRoot: Path,
    ): List<StoredBackupRecord> {
        val canonical = canonicalResolver.resolve(skill)?.takeIf { it.scope == scope } ?: return emptyList()
        val key = instanceKey(canonical.skillId, scope, project, canonical.canonicalPath)
        return BackupMetadataStore.listBackups(backupRoot)
            .filter { it.instanceKey == key }
            .sortedByDescending { it.backup.createdAt }
    }

    /** One backup-tree scan for all skills in a browser snapshot; safe to call on a pooled thread. */
    fun skillIdsWithBackups(
        skills: List<AgentSkill>,
        scope: SkillScope,
        project: DiscoveredProject?,
        backupRoot: Path,
    ): Set<String> {
        val keysWithBackups = BackupMetadataStore.listBackups(backupRoot).mapTo(mutableSetOf(), StoredBackupRecord::instanceKey)
        return skills.mapNotNullTo(mutableSetOf()) { skill ->
            val canonical = canonicalResolver.resolve(skill)?.takeIf { it.scope == scope } ?: return@mapNotNullTo null
            val key = instanceKey(canonical.skillId, scope, project, canonical.canonicalPath)
            canonical.skillId.takeIf { key in keysWithBackups }
        }
    }

    /**
     * One backup-tree scan plus bounded journal reads for a history dialog. An operation is undoable while its
     * journal exists and every backup it needs is still there; [UndoAvailability.backupRemoved] marks the ones
     * that have a journal but lost a backup (retention, or the user deleted it), so the UI can say why.
     */
    fun undoAvailability(operationIds: Set<String>, backupRoot: Path): UndoAvailability {
        if (operationIds.isEmpty()) return UndoAvailability(emptySet(), emptySet())
        val backupPathsByOperation = BackupMetadataStore.listBackups(backupRoot)
            .groupBy { it.backup.operationId }
            .mapValues { (_, records) -> records.mapTo(mutableSetOf()) { it.backup.originalPath } }
        val undoable = mutableSetOf<String>()
        val backupRemoved = mutableSetOf<String>()
        operationIds.forEach { operationId ->
            val journal = OperationJournalStore.read(backupRoot, operationId) ?: return@forEach
            val required = journal.reversalSteps
                .filter(PersistedReversalStep::requiresBackup)
                .mapTo(mutableSetOf()) { Path.of(it.path) }
            val present = backupPathsByOperation[operationId].orEmpty()
            if (required.all { it in present }) undoable += operationId else backupRemoved += operationId
        }
        return UndoAvailability(undoable, backupRemoved)
    }

    /**
     * Restores one specific on-disk backup, independent of any in-memory [SkillSyncResult] —
     * unlike [UndoService], this works even after an IDE restart, since [restorableBackups] reads
     * purely from disk. Whatever currently sits at the target path is backed up first (under a
     * fresh operation id) so this itself can be reversed with another restore later.
     */
    fun restoreBackup(record: StoredBackupRecord, backupRoot: Path, operationId: String): SkillSyncResult {
        val originalPath = record.backup.originalPath
        var safetyBackup: SkillBackup? = null
        if (Files.exists(originalPath, LinkOption.NOFOLLOW_LINKS)) {
            safetyBackup = backupService.backup(
                record.agentId,
                record.instanceKey.skillId,
                originalPath,
                backupRoot,
                operationId,
                instanceKey = record.instanceKey,
            )
            if (safetyBackup == null) {
                return restoreResult(
                    record,
                    operationId,
                    restored = false,
                    "Could not back up the current content; restore was not attempted.",
                )
            }
        }
        val restored = backupService.restore(record.backup)
        return restoreResult(
            record,
            operationId,
            restored,
            if (restored) null else "Could not restore the selected backup; current content was left unchanged.",
            safetyBackup,
        )
    }

    private fun restoreResult(
        record: StoredBackupRecord,
        operationId: String,
        restored: Boolean,
        errorMessage: String?,
        safetyBackup: SkillBackup? = null,
    ): SkillSyncResult {
        val status = if (restored) SyncOperationStatus.SUCCESS else SyncOperationStatus.FAILED
        auditTrail.record(
            SyncAuditEntry(
                operationId = operationId,
                timestamp = now(),
                skillId = record.instanceKey.skillId,
                action = SyncAction.RESTORE_BACKUP,
                affectedAgents = setOf(record.agentId),
                result = status,
                instanceKey = record.instanceKey,
            ),
        )
        return SkillSyncResult(
            operationId = operationId,
            status = status,
            appliedSteps = emptyList(),
            errors = errorMessage?.let { listOf(SyncError(record.agentId, it)) }.orEmpty(),
            rollbackAvailable = false,
            restorableBackups = listOfNotNull(safetyBackup),
            skillId = record.instanceKey.skillId,
            instanceKey = record.instanceKey,
            canonicalPath = null,
            targetResults = listOf(
                SkillSyncTargetResult(
                    record.agentId,
                    if (restored) SyncTargetOutcome.CHANGED else SyncTargetOutcome.FAILED,
                    errorMessage,
                ),
            ),
        )
    }

    fun undo(result: SkillSyncResult): UndoResult = undoService.undo(result)

    /** Builds the mandatory, mutation-free confirmation model for a persisted Undo. */
    fun previewUndoOperation(operationId: String, backupRoot: Path): UndoPreview? {
        val journal = OperationJournalStore.read(backupRoot, operationId) ?: return null
        val backups = BackupMetadataStore.listBackups(backupRoot)
            .filter { it.backup.operationId == operationId }
        val backedUpPaths = backups.mapTo(mutableSetOf()) { it.backup.originalPath }
        val requiredBackupPaths = journal.reversalSteps
            .filter(PersistedReversalStep::requiresBackup)
            .mapTo(mutableSetOf()) { Path.of(it.path) }
        if (!requiredBackupPaths.all { it in backedUpPaths }) return null
        return UndoPreview(
            operationId = operationId,
            skillId = journal.skillId,
            affectedAgents = (journal.reversalSteps.map { it.agentId } + journal.previousManagedTargets.keys).toSet(),
            affectedPaths = journal.reversalSteps.map { it.path }.distinct(),
            backupCount = backups.size,
        )
    }

    /** Read-only batch used by the Skills dashboard; performs the same observation as planning. */
    fun targetStatuses(
        skill: AgentSkill,
        scope: SkillScope,
        project: DiscoveredProject?,
        targetsByAgentId: Map<String, SkillSyncTarget>,
        requestedMode: SkillSyncMode = SkillSyncMode.SYMLINK,
    ): List<ObservedSkillTarget> {
        val canonical = canonicalResolver.resolve(skill)?.takeIf { it.scope == scope } ?: return emptyList()
        val key = instanceKey(canonical.skillId, scope, project, canonical.canonicalPath)
        val currentCanonicalFingerprint = fingerprint.calculate(canonical.canonicalPath)
        return targetsByAgentId.values.sortedBy { it.agentId }.map { target ->
            observer.observe(
                target,
                canonical.canonicalPath,
                currentCanonicalFingerprint,
                scope,
                project,
                requestedMode,
                ownershipStore.managedTarget(key, target.agentId),
            )
        }
    }

    /**
     * Reverses a past operation purely from disk — the [OperationJournalStore] entry
     * [SkillSyncExecutor] wrote plus whatever [BackupMetadataStore] still has for that
     * `operationId` — so this works even after an IDE restart lost the in-memory
     * [SkillSyncResult]. Returns null when there's no journal (nothing reversible was ever
     * recorded for that operation, e.g. a Restore Backup or an operation that made no changes).
     */
    fun undoOperation(operationId: String, backupRoot: Path): UndoResult? {
        val journal = OperationJournalStore.read(backupRoot, operationId) ?: return null
        val backups = BackupMetadataStore.listBackups(backupRoot)
            .filter { it.backup.operationId == operationId }
            .map { it.backup }
        val requiredBackupPaths = journal.reversalSteps
            .filter(PersistedReversalStep::requiresBackup)
            .mapTo(mutableSetOf()) { Path.of(it.path) }
        val reconstructed = SkillSyncResult(
            operationId = journal.operationId,
            status = SyncOperationStatus.SUCCESS,
            appliedSteps = reconstructAppliedSteps(journal),
            errors = emptyList(),
            rollbackAvailable = true,
            restorableBackups = backups,
            skillId = journal.skillId,
            previousManagedTargets = journal.previousManagedTargets,
            instanceKey = journal.instanceKey,
            canonicalPath = journal.canonicalPath,
            requiredBackupPaths = requiredBackupPaths,
        )
        val undoResult = undoService.undo(reconstructed)
        auditTrail.record(
            SyncAuditEntry(
                operationId = operationId,
                timestamp = now(),
                skillId = journal.skillId,
                action = SyncAction.UNDO,
                affectedAgents = journal.previousManagedTargets.keys,
                result = if (undoResult.errors.isEmpty()) SyncOperationStatus.ROLLED_BACK else SyncOperationStatus.FAILED,
                instanceKey = journal.instanceKey,
            ),
        )
        return undoResult
    }

    private fun reconstructAppliedSteps(journal: PersistedOperationJournal): List<SkillSyncStepResult> {
        val reversal = journal.reversalSteps.map { step ->
            val syncStep: SkillSyncStep = when (step.kind) {
                PersistedStepKind.CREATE_LINK ->
                    SkillSyncStep.CreateLink(step.agentId, journal.canonicalPath, Path.of(step.path), SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK)
                PersistedStepKind.COPY_SKILL -> SkillSyncStep.CopySkill(step.agentId, journal.canonicalPath, Path.of(step.path))
                PersistedStepKind.REMOVE_EXISTING -> SkillSyncStep.RemoveExisting(step.agentId, Path.of(step.path))
            }
            SkillSyncStepResult(syncStep, succeeded = true)
        }
        val verifications = journal.verifications.map { verification ->
            SkillSyncStepResult(
                SkillSyncStep.VerifyFingerprint(verification.agentId, Path.of(verification.path), verification.expectedFingerprint),
                succeeded = true,
            )
        }
        return reversal + verifications
    }

    private fun instanceKey(
        skillId: String,
        scope: SkillScope,
        project: DiscoveredProject?,
        canonicalPath: Path,
    ) = resolveInstanceKey(skillId, scope, project, canonicalPath, runtimeId)
}

/** Which history operations can still be undone, and which of the rest only lack a removed backup. */
data class UndoAvailability(val undoable: Set<String>, val backupRemoved: Set<String>)
