package com.shutterstar.agenthub.environment.skills.sync

import com.shutterstar.agenthub.environment.skills.discovery.SharedSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SkillFingerprint
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.audit.InMemorySyncAuditTrail
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAction
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAuditEntry
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAuditTrail
import com.shutterstar.agenthub.environment.skills.sync.execution.BackupService
import com.shutterstar.agenthub.environment.skills.sync.execution.SkillSyncExecutor
import com.shutterstar.agenthub.environment.skills.sync.execution.SkillSyncStepExecutor
import com.shutterstar.agenthub.environment.skills.sync.execution.StoredBackupRecord
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncPlan
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncRequest
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncResult
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.ownership.InMemorySyncOwnershipStore
import com.shutterstar.agenthub.environment.skills.sync.ownership.SyncOwnershipStore
import com.shutterstar.agenthub.environment.skills.sync.planning.CanonicalSkillResolver
import com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillSyncPlanner
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillSyncPlanningRequest
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillTargetObserver
import com.shutterstar.agenthub.environment.skills.sync.undo.UndoPreview
import com.shutterstar.agenthub.environment.skills.sync.undo.UndoResult
import com.shutterstar.agenthub.environment.skills.sync.undo.UndoService
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path
import java.time.Instant

data class SkillSyncPlanResult(
    val plan: SkillSyncPlan,
    val planningRequest: SkillSyncPlanningRequest,
    val action: SyncAction,
    val scope: SkillScope,
)

/**
 * Public entry point for turning a user-facing [SkillSyncRequest] into a plan and, once approved,
 * executing it. This class is a thin facade that keeps the original constructor and method
 * surface stable for its ~5 call sites (notably [SkillSyncApplicationService] and the direct
 * constructors in tests); the actual work is split across two focused collaborators so neither
 * grows into a God class:
 * - [SkillSyncRequestPlanner] — `plan()` and everything about turning a request into a
 *   [SkillSyncPlanResult], including `RepairSkill`'s whole-skill mode and all four
 *   `ResolveConflict` resolutions (`KEEP_CANONICAL`/`KEEP_TARGET`/`CANCEL`/`KEEP_BOTH`).
 * - [SkillSyncOperationRunner] — `execute()`, undo (both the immediate in-memory path and the
 *   disk-persisted [undoOperation] path that survives an IDE restart), backup restore, and the
 *   read-only history/status queries.
 *
 * Both collaborators derive the same [com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey]
 * via the shared [resolveInstanceKey] helper, so a plan built by one and executed by the other
 * always agree on ownership/audit identity.
 */
internal class SkillSyncService(
    private val canonicalResolver: CanonicalSkillResolver = CanonicalSkillResolver(),
    private val ownershipStore: SyncOwnershipStore = InMemorySyncOwnershipStore(),
    private val auditTrail: SyncAuditTrail = InMemorySyncAuditTrail(),
    private val observer: SkillTargetObserver = SkillTargetObserver(),
    private val planner: SkillSyncPlanner = SkillSyncPlanner(),
    private val backupService: BackupService = BackupService(),
    private val executor: SkillSyncExecutor = SkillSyncExecutor(
        stepExecutor = SkillSyncStepExecutor(backupService = backupService, ownershipStore = ownershipStore),
        observer = observer,
        ownershipStore = ownershipStore,
        backupService = backupService,
    ),
    private val fingerprint: SkillFingerprint = SkillFingerprint(),
    private val sharedSkillDirectory: SharedSkillProvider = SharedSkillProvider(),
    private val now: () -> Instant = Instant::now,
    private val undoService: UndoService = UndoService(ownershipStore = ownershipStore),
    private val runtimeId: String = "host",
) {
    private val requestPlanner = SkillSyncRequestPlanner(
        canonicalResolver = canonicalResolver,
        observer = observer,
        planner = planner,
        fingerprint = fingerprint,
        sharedSkillDirectory = sharedSkillDirectory,
        ownershipStore = ownershipStore,
        runtimeId = runtimeId,
    )

    private val operationRunner = SkillSyncOperationRunner(
        executor = executor,
        auditTrail = auditTrail,
        undoService = undoService,
        ownershipStore = ownershipStore,
        canonicalResolver = canonicalResolver,
        observer = observer,
        fingerprint = fingerprint,
        backupService = backupService,
        now = now,
        runtimeId = runtimeId,
    )

    fun plan(
        request: SkillSyncRequest,
        skill: AgentSkill,
        operationId: String,
        targetsByAgentId: Map<String, SkillSyncTarget>,
        installedAgentIds: Set<String>,
        scope: SkillScope,
        project: DiscoveredProject?,
        backupBeforeReplacement: Boolean = true,
        manageExistingTargets: Boolean = false,
    ): SkillSyncPlanResult = requestPlanner.plan(
        request,
        skill,
        operationId,
        targetsByAgentId,
        installedAgentIds,
        scope,
        project,
        backupBeforeReplacement,
        manageExistingTargets,
    )

    /** Read-only hint for UI action gating; see [SkillSyncOperationRunner.managedTargetIds]. */
    fun managedTargetIds(skill: AgentSkill, scope: SkillScope, project: DiscoveredProject?): Set<String> =
        operationRunner.managedTargetIds(skill, scope, project)

    /** Read-only: the persisted, most-recent-first audit log for this skill instance. */
    fun historyFor(skill: AgentSkill, scope: SkillScope, project: DiscoveredProject?, limit: Int = 50): List<SyncAuditEntry> =
        operationRunner.historyFor(skill, scope, project, limit)

    fun execute(
        result: SkillSyncPlanResult,
        targetsByAgentId: Map<String, SkillSyncTarget>,
        scope: SkillScope,
        project: DiscoveredProject?,
        backupRoot: Path,
    ): SkillSyncResult = operationRunner.execute(result, targetsByAgentId, scope, project, backupRoot)

    /** Read-only: every backup on disk for this skill instance, most recent first. */
    fun restorableBackups(
        skill: AgentSkill,
        scope: SkillScope,
        project: DiscoveredProject?,
        backupRoot: Path,
    ): List<StoredBackupRecord> = operationRunner.restorableBackups(skill, scope, project, backupRoot)

    fun skillIdsWithBackups(
        skills: List<AgentSkill>,
        scope: SkillScope,
        project: DiscoveredProject?,
        backupRoot: Path,
    ): Set<String> = operationRunner.skillIdsWithBackups(skills, scope, project, backupRoot)

    fun undoAvailability(operationIds: Set<String>, backupRoot: Path): UndoAvailability =
        operationRunner.undoAvailability(operationIds, backupRoot)

    /** Restores one specific on-disk backup; see [SkillSyncOperationRunner.restoreBackup]. */
    fun restoreBackup(record: StoredBackupRecord, backupRoot: Path, operationId: String): SkillSyncResult =
        operationRunner.restoreBackup(record, backupRoot, operationId)

    fun undo(result: SkillSyncResult): UndoResult = operationRunner.undo(result)

    /** Builds the mandatory, mutation-free confirmation model for a persisted Undo. */
    fun previewUndoOperation(operationId: String, backupRoot: Path): UndoPreview? =
        operationRunner.previewUndoOperation(operationId, backupRoot)

    /** Read-only batch used by the Skills dashboard; performs the same observation as planning. */
    fun targetStatuses(
        skill: AgentSkill,
        scope: SkillScope,
        project: DiscoveredProject?,
        targetsByAgentId: Map<String, SkillSyncTarget>,
        requestedMode: SkillSyncMode = SkillSyncMode.SYMLINK,
    ): List<ObservedSkillTarget> = operationRunner.targetStatuses(skill, scope, project, targetsByAgentId, requestedMode)

    /** Reverses a past operation purely from disk; see [SkillSyncOperationRunner.undoOperation]. */
    fun undoOperation(operationId: String, backupRoot: Path): UndoResult? =
        operationRunner.undoOperation(operationId, backupRoot)
}
