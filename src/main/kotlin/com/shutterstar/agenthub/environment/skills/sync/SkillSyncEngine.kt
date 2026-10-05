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
 * Wires the two collaborators that turn a user-facing [SkillSyncRequest] into a plan and execute it,
 * sharing one set of stores and helpers so a plan built by one and executed by the other always
 * agree on ownership/audit identity ([resolveInstanceKey]):
 * - [planner] ([SkillSyncRequestPlanner]) — `plan()` and everything about turning a request into a
 *   [SkillSyncPlanResult], including `RepairSkill`'s whole-skill mode and all four
 *   `ResolveConflict` resolutions.
 * - [runner] ([SkillSyncOperationRunner]) — `execute()`, undo (in-memory and disk-persisted, surviving
 *   an IDE restart), backup restore, and the read-only history/status queries.
 *
 * There are deliberately no pass-through methods here: callers use the two collaborators directly.
 * The constructor defaults give tests a fully in-memory engine.
 */
internal class SkillSyncEngine(
    private val canonicalResolver: CanonicalSkillResolver = CanonicalSkillResolver(),
    private val ownershipStore: SyncOwnershipStore = InMemorySyncOwnershipStore(),
    private val auditTrail: SyncAuditTrail = InMemorySyncAuditTrail(),
    private val observer: SkillTargetObserver = SkillTargetObserver(),
    private val stepPlanner: SkillSyncPlanner = SkillSyncPlanner(),
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
    val planner = SkillSyncRequestPlanner(
        canonicalResolver = canonicalResolver,
        observer = observer,
        planner = stepPlanner,
        fingerprint = fingerprint,
        sharedSkillDirectory = sharedSkillDirectory,
        ownershipStore = ownershipStore,
        runtimeId = runtimeId,
    )

    val runner = SkillSyncOperationRunner(
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
}
