package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.environment.skills.discovery.SkillFingerprint
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncPlan
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncResult
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStep
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStepResult
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTargetResult
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SyncError
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SyncTargetOutcome
import com.shutterstar.agenthub.environment.skills.sync.ownership.InMemorySyncOwnershipStore
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import com.shutterstar.agenthub.environment.skills.sync.ownership.SyncOwnershipStore
import com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillSyncPlanningRequest
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillTargetObserver
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path

/**
 * Turns an already-previewed [SkillSyncPlan] into real filesystem changes: revalidates each
 * target immediately before touching it (spec §69 — never apply a stale plan), executes its steps
 * in order (coalescing each planned `RemoveExisting` + following `CreateLink`/`CopySkill` on the
 * same path into one [AtomicPathReplace] install-then-swap), and rolls back that target's own
 * partial changes if any step fails. Independent targets are never affected by one another's
 * failure (spec §104 partial-success semantics).
 */
internal class SkillSyncExecutor(
    private val ownershipStore: SyncOwnershipStore = InMemorySyncOwnershipStore(),
    private val stepExecutor: SkillSyncStepExecutor = SkillSyncStepExecutor(ownershipStore = ownershipStore),
    private val observer: SkillTargetObserver = SkillTargetObserver(),
    private val backupService: BackupService = BackupService(),
    private val fingerprint: SkillFingerprint = SkillFingerprint(),
) {
    fun execute(
        plan: SkillSyncPlan,
        request: SkillSyncPlanningRequest,
        targetsByAgentId: Map<String, SkillSyncTarget>,
        scope: SkillScope,
        project: DiscoveredProject?,
        backupRoot: Path,
    ): SkillSyncResult {
        val originalByAgentId = request.targets.associateBy { it.agentId }
        val stepsByAgentId = plan.steps.groupBy(SkillSyncStep::agentId)

        val appliedSteps = mutableListOf<SkillSyncStepResult>()
        val errors = mutableListOf<SyncError>()
        val rollbackErrors = mutableListOf<SyncError>()
        val backups = mutableListOf<SkillBackup>()
        val previousManagedTargets = mutableMapOf<String, ManagedTarget?>()
        val targetResults = linkedMapOf<String, SkillSyncTargetResult>()
        var succeededAgents = 0
        var failedAgents = 0

        for (target in request.targets) {
            if (stepsByAgentId[target.agentId].isNullOrEmpty()) {
                val outcome = when (target.status) {
                    SkillTargetStatus.LINKED, SkillTargetStatus.NATIVE -> if (plan.warnings.none { it.agentId == target.agentId }) {
                        SyncTargetOutcome.ALREADY_AVAILABLE
                    } else {
                        SyncTargetOutcome.BLOCKED
                    }
                    SkillTargetStatus.UNSUPPORTED ->
                        SyncTargetOutcome.UNSUPPORTED
                    else -> SyncTargetOutcome.BLOCKED
                }
                targetResults[target.agentId] = SkillSyncTargetResult(
                    target.agentId,
                    outcome,
                    plan.warnings.firstOrNull { it.agentId == target.agentId }?.message,
                )
            }
        }

        for ((agentId, steps) in stepsByAgentId) {
            if (fingerprint.calculate(request.canonicalPath) != request.observedCanonicalFingerprint) {
                errors += SyncError(agentId, "Shared source changed since preview.")
                targetResults[agentId] = SkillSyncTargetResult(agentId, SyncTargetOutcome.FAILED, "Shared source changed since preview.")
                failedAgents++
                continue
            }

            val original = originalByAgentId[agentId]
            val target = targetsByAgentId[agentId]
            if (original == null || target == null) {
                errors += SyncError(agentId, "No observed target or sync target adapter for $agentId.")
                targetResults[agentId] = SkillSyncTargetResult(agentId, SyncTargetOutcome.FAILED, "Target adapter is unavailable.")
                failedAgents++
                continue
            }

            if (hasDrifted(target, original, request, scope, project)) {
                errors += SyncError(agentId, "Environment changed since preview.")
                targetResults[agentId] = SkillSyncTargetResult(agentId, SyncTargetOutcome.FAILED, "Environment changed since preview.")
                failedAgents++
                continue
            }

            val previousManagedTarget = ownershipStore.managedTarget(request.instanceKey, agentId)
            val outcome = executeGroup(steps, backupRoot, request.skillId, request.instanceKey, plan.operationId)
            appliedSteps += outcome.results
            backups += outcome.backups

            if (outcome.failure != null) {
                if (outcome.rollbackErrors.isEmpty()) {
                    restoreOwnership(request.instanceKey, agentId, previousManagedTarget)
                }
                errors += SyncError(agentId, outcome.failure)
                val groupRollbackErrors = outcome.rollbackErrors.map { SyncError(agentId, it) }
                errors += groupRollbackErrors
                rollbackErrors += groupRollbackErrors
                targetResults[agentId] = SkillSyncTargetResult(
                    agentId,
                    SyncTargetOutcome.FAILED,
                    (listOf(outcome.failure) + outcome.rollbackErrors).joinToString(" "),
                )
                failedAgents++
            } else {
                previousManagedTargets[agentId] = previousManagedTarget
                targetResults[agentId] = SkillSyncTargetResult(agentId, SyncTargetOutcome.CHANGED)
                succeededAgents++
            }
        }

        val successfulTargets = targetResults.values.count { it.outcome in SUCCESSFUL_TARGET_OUTCOMES }
        val unsuccessfulTargets = targetResults.size - successfulTargets
        val status = when {
            targetResults.isNotEmpty() && unsuccessfulTargets == 0 -> SyncOperationStatus.SUCCESS
            targetResults.isNotEmpty() && successfulTargets > 0 -> SyncOperationStatus.PARTIAL_SUCCESS
            targetResults.isNotEmpty() -> SyncOperationStatus.FAILED
            failedAgents == 0 && (plan.steps.isNotEmpty() || plan.warnings.isEmpty()) -> SyncOperationStatus.SUCCESS
            succeededAgents > 0 -> SyncOperationStatus.PARTIAL_SUCCESS
            else -> SyncOperationStatus.FAILED
        }

        val requiredBackupPaths = appliedSteps
            .filter { it.succeeded }
            .mapNotNull { (it.step as? SkillSyncStep.RemoveExisting)?.path }
            .toSet()
        val journalPersisted = persistUndoJournal(
            plan.operationId,
            request,
            appliedSteps,
            previousManagedTargets,
            requiredBackupPaths,
            backupRoot,
        )
        if (!journalPersisted) {
            errors += SyncError(null, "Changes were applied, but the Undo journal could not be saved.")
        }
        val finalStatus = if (!journalPersisted && status == SyncOperationStatus.SUCCESS) {
            SyncOperationStatus.PARTIAL_SUCCESS
        } else {
            status
        }
        val backupPaths = backups.mapTo(mutableSetOf(), SkillBackup::originalPath)
        val reversibleMutations = appliedSteps.any { result ->
            result.succeeded &&
                result.step.agentId in previousManagedTargets &&
                result.step.reversalTarget() != null
        }

        return SkillSyncResult(
            operationId = plan.operationId,
            status = finalStatus,
            appliedSteps = appliedSteps,
            errors = errors,
            rollbackAvailable = reversibleMutations && requiredBackupPaths.all { it in backupPaths },
            restorableBackups = backups,
            skillId = request.skillId,
            previousManagedTargets = previousManagedTargets,
            instanceKey = request.instanceKey,
            canonicalPath = request.canonicalPath,
            targetResults = targetResults.values.toList(),
            rollbackErrors = rollbackErrors,
            requiredBackupPaths = requiredBackupPaths,
        )
    }

    /**
     * Best-effort: a journal-write failure must never fail the sync operation it's describing.
     * Only steps [UndoService][com.shutterstar.agenthub.environment.skills.sync.undo.UndoService]
     * can actually reverse are persisted; an operation with nothing reversible writes no journal.
     */
    private fun persistUndoJournal(
        operationId: String,
        request: SkillSyncPlanningRequest,
        appliedSteps: List<SkillSyncStepResult>,
        previousManagedTargets: Map<String, ManagedTarget?>,
        requiredBackupPaths: Set<Path>,
        backupRoot: Path,
    ): Boolean {
        val reversalSteps = appliedSteps.filter { it.succeeded }.mapNotNull { result ->
            when (val step = result.step) {
                is SkillSyncStep.CreateLink ->
                    PersistedReversalStep(step.agentId, PersistedStepKind.CREATE_LINK, step.target.toString(), step.target in requiredBackupPaths)
                is SkillSyncStep.CopySkill ->
                    PersistedReversalStep(step.agentId, PersistedStepKind.COPY_SKILL, step.target.toString(), step.target in requiredBackupPaths)
                is SkillSyncStep.RemoveExisting ->
                    PersistedReversalStep(step.agentId, PersistedStepKind.REMOVE_EXISTING, step.path.toString(), requiresBackup = true)
                else -> null
            }
        }
        if (reversalSteps.isEmpty()) return true

        val verifications = appliedSteps.filter { it.succeeded }.mapNotNull { result ->
            (result.step as? SkillSyncStep.VerifyFingerprint)?.let {
                PersistedVerification(it.agentId, it.path.toString(), it.expectedFingerprint)
            }
        }
        return runCatching {
            OperationJournalStore.write(
                PersistedOperationJournal(
                    operationId = operationId,
                    skillId = request.skillId,
                    instanceKey = request.instanceKey,
                    canonicalPath = request.canonicalPath,
                    reversalSteps = reversalSteps,
                    verifications = verifications,
                    previousManagedTargets = previousManagedTargets,
                ),
                backupRoot,
            )
        }.isSuccess
    }

    private fun hasDrifted(
        target: SkillSyncTarget,
        original: ObservedSkillTarget,
        request: SkillSyncPlanningRequest,
        scope: SkillScope,
        project: DiscoveredProject?,
    ): Boolean {
        val fresh = observer.observe(
            target = target,
            canonicalPath = request.canonicalPath,
            canonicalFingerprint = request.canonicalFingerprint,
            scope = scope,
            project = project,
            requestedMode = original.requestedMode,
            managedTarget = ownershipStore.managedTarget(request.instanceKey, target.agentId),
            nativeShortCircuit = request.nativeShortCircuit,
        )
        return fresh != original
    }

    private fun executeGroup(
        steps: List<SkillSyncStep>,
        backupRoot: Path,
        skillId: String,
        instanceKey: SkillInstanceKey,
        operationId: String,
    ): GroupOutcome {
        val context = StepExecutionContext(operationId, skillId, backupRoot, instanceKey)
        val results = mutableListOf<SkillSyncStepResult>()
        val capturedBackups = mutableListOf<SkillBackup>()

        var index = 0
        while (index < steps.size) {
            val step = steps[index]
            val next = steps.getOrNull(index + 1)
            val coalescedInstall = next?.takeIf { step is SkillSyncStep.RemoveExisting && installsAt(it, step.path) }

            val outcome = if (step is SkillSyncStep.RemoveExisting && coalescedInstall != null) {
                runCatching { stepExecutor.replaceExisting(step, coalescedInstall, context) }
                    .getOrElse { StepOutcome.Failure(it.message ?: "Failed to replace ${step.path}") }
            } else {
                runCatching { stepExecutor.execute(step, context) }
                    .getOrElse { StepOutcome.Failure(it.message ?: "Failed to execute $step") }
            }

            when (outcome) {
                is StepOutcome.Success -> {
                    results += SkillSyncStepResult(step, succeeded = true)
                    outcome.backup?.let(capturedBackups::add)
                    if (coalescedInstall != null) {
                        results += SkillSyncStepResult(coalescedInstall, succeeded = true)
                        index += 2
                    } else {
                        index += 1
                    }
                }

                is StepOutcome.Failure -> {
                    results += SkillSyncStepResult(step, succeeded = false, error = outcome.message)
                    val failedStep = coalescedInstall ?: step
                    if (coalescedInstall != null) {
                        results += SkillSyncStepResult(coalescedInstall, succeeded = false, error = outcome.message)
                    }
                    val rollbackErrors = rollback(failedStep, results, capturedBackups)
                    return GroupOutcome(results, capturedBackups, outcome.message, rollbackErrors)
                }
            }
        }

        return GroupOutcome(results, capturedBackups, failure = null, rollbackErrors = emptyList())
    }

    private fun installsAt(step: SkillSyncStep, path: Path): Boolean {
        val installPath = when (step) {
            is SkillSyncStep.CreateLink -> step.target
            is SkillSyncStep.CopySkill -> step.target
            else -> return false
        }
        return samePath(installPath, path)
    }

    private fun samePath(left: Path, right: Path): Boolean =
        left == right || left.toAbsolutePath().normalize() == right.toAbsolutePath().normalize()


    private fun rollback(
        failedStep: SkillSyncStep,
        results: List<SkillSyncStepResult>,
        backups: List<SkillBackup>,
    ): List<String> {
        val errors = mutableListOf<String>()
        val backupsByPath = backups.associateBy(SkillBackup::originalPath)
        val successfulMutations = results
            .filter { it.succeeded }
            .mapNotNull { result -> result.step.reversalTarget()?.let { path -> path to result.step.createsContent() } }
        val failedMutation = failedStep.reversalTarget()?.let { path ->
            if (failedStep.createsContent() || backupsByPath.containsKey(path)) path to failedStep.createsContent() else null
        }
        val targets = (successfulMutations + listOfNotNull(failedMutation))
            .asReversed()
            .distinctBy { it.first }

        for ((path, createdContent) in targets) {
            val backup = backupsByPath[path]
            if (backup != null) {
                val restored = runCatching { backupService.restore(backup) }
                    .getOrElse {
                        errors += "Rollback restore failed for $path: ${it.message ?: it.javaClass.simpleName}."
                        false
                    }
                if (!restored && errors.none { path.toString() in it }) {
                    errors += "Rollback could not restore backup for $path."
                }
            } else if (createdContent) {
                runCatching { DirectoryDeleter.deleteRecursively(path) }
                    .onFailure { errors += "Rollback could not remove $path: ${it.message ?: it.javaClass.simpleName}." }
            }
        }
        return errors
    }

    private fun restoreOwnership(
        instanceKey: SkillInstanceKey,
        agentId: String,
        previous: ManagedTarget?,
    ) {
        if (previous == null) {
            ownershipStore.remove(instanceKey, agentId)
        } else {
            ownershipStore.record(instanceKey, previous)
        }
    }

    private fun SkillSyncStep.reversalTarget(): Path? = when (this) {
        is SkillSyncStep.CreateLink -> target
        is SkillSyncStep.CopySkill -> target
        is SkillSyncStep.RemoveExisting -> path
        else -> null
    }

    private fun SkillSyncStep.createsContent(): Boolean = this is SkillSyncStep.CreateLink || this is SkillSyncStep.CopySkill

    private data class GroupOutcome(
        val results: List<SkillSyncStepResult>,
        val backups: List<SkillBackup>,
        val failure: String?,
        val rollbackErrors: List<String>,
    )

    private companion object {
        val SUCCESSFUL_TARGET_OUTCOMES = setOf(SyncTargetOutcome.CHANGED, SyncTargetOutcome.ALREADY_AVAILABLE)
    }
}
