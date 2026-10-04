package com.shutterstar.agenthub.environment.skills.sync

import com.shutterstar.agenthub.OsDetector
import com.shutterstar.agenthub.environment.capabilities.AgentCapabilityRegistry
import com.shutterstar.agenthub.environment.skills.discovery.SharedSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SkillFingerprint
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAction
import com.shutterstar.agenthub.environment.skills.sync.model.ConflictResolution
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncPlan
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncRequest
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStep
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SkillDirectoryName
import com.shutterstar.agenthub.environment.skills.sync.model.SyncWarning
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.environment.skills.sync.ownership.SyncOwnershipStore
import com.shutterstar.agenthub.environment.skills.sync.planning.CanonicalSkillResolver
import com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillSyncPlanner
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillSyncPlanningRequest
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillTargetObserver
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Everything a `plan*` helper needs about the request's canonical identity, bundled once instead
 * of threaded through every method as 7-9 positional parameters — the actual data clump that used
 * to make [SkillSyncRequestPlanner]'s private methods risky to reorder by hand. [planPromote] is
 * deliberately excluded: it derives `canonicalPath` itself mid-method (nothing to resolve yet), so
 * forcing it into this shape would hide, not clarify, that it works differently from the rest.
 */
private data class PlanContext(
    val operationId: String,
    val skillId: String,
    val canonicalPath: Path,
    val canonicalFingerprint: String?,
    val scope: SkillScope,
    val project: DiscoveredProject?,
    val backupBeforeReplacement: Boolean = true,
    /** See [com.shutterstar.agenthub.environment.skills.sync.settings.SkillSyncSettings.manageExistingTargets]. */
    val manageExistingTargets: Boolean = false,
)

/**
 * Translates a user-facing [SkillSyncRequest] into a [SkillSyncPlan] — the read-only, no-I/O-beyond-
 * observation half of [SkillSyncService]. Execution, undo and history live in [SkillSyncOperationRunner];
 * the two are split only to keep each file focused, they still share [ownershipStore] and [runtimeId]
 * (via [resolveInstanceKey]) so a plan built here and later executed there resolves to the same
 * [com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey].
 *
 * `RepairSkill` with `targetAgentId == null` repairs every currently-managed agent for the skill
 * instance in one plan (see [planWholeSkillRepair]). `ResolveConflict`'s
 * `KEEP_CANONICAL`/`KEEP_TARGET`/`CANCEL`/`KEEP_BOTH` are all implemented (each its own private
 * method — [planKeepCanonical], [planKeepTarget], [planKeepBoth]). `KEEP_BOTH` only ever copies the
 * diverged target's content to a new, caller-chosen name next to itself — it never fabricates a
 * `SkillIdentity` for the copy (that stays Milestone 2 discovery-layer scope this service doesn't
 * own); discovery finds it as its own skill, with its own identity, next scan.
 */
internal class SkillSyncRequestPlanner(
    private val canonicalResolver: CanonicalSkillResolver,
    private val observer: SkillTargetObserver,
    private val planner: SkillSyncPlanner,
    private val fingerprint: SkillFingerprint,
    private val sharedSkillDirectory: SharedSkillProvider,
    private val ownershipStore: SyncOwnershipStore,
    private val runtimeId: String,
) {
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
    ): SkillSyncPlanResult {
        val action = request.toAction()

        if (request is SkillSyncRequest.PromoteSkill) {
            if (scope != request.scope) {
                return blankResult(
                    operationId,
                    request.skillId,
                    request.sourcePath,
                    action,
                    request.scope,
                    "Requested ${request.scope} scope does not match the supplied $scope scope.",
                )
            }
            return planPromote(operationId, request, targetsByAgentId, request.scope, project, backupBeforeReplacement)
        }

        if (request is SkillSyncRequest.ReplaceCopy) {
            if (scope != request.scope) {
                return blankResult(
                    operationId,
                    request.skillId,
                    request.sourcePath,
                    action,
                    request.scope,
                    "Requested ${request.scope} scope does not match the supplied $scope scope.",
                )
            }
            return planReplaceCopy(operationId, request, targetsByAgentId, request.scope, project)
        }

        val canonical = canonicalResolver.resolve(skill)
            ?: return emptyResult(operationId, request.skillId, skill, action, scope, "Skill has not been promoted to a shared location yet.")

        if (scope != canonical.scope) {
            return blankResult(
                operationId,
                canonical.skillId,
                canonical.canonicalPath,
                action,
                canonical.scope,
                "Canonical ${canonical.scope} scope does not match the supplied $scope scope.",
            )
        }

        val context = PlanContext(
            operationId,
            canonical.skillId,
            canonical.canonicalPath,
            canonical.fingerprint,
            scope,
            project,
            backupBeforeReplacement,
            manageExistingTargets,
        )

        return when (request) {
            is SkillSyncRequest.ShareSkill -> planShare(context, request.targetAgentId, request.mode, targetsByAgentId)

            is SkillSyncRequest.ShareSkillEverywhere -> planShareEverywhere(context, request.mode, targetsByAgentId, installedAgentIds)

            is SkillSyncRequest.StopSharing -> planStopSharing(context, request.targetAgentId, targetsByAgentId)

            is SkillSyncRequest.RemoveRedundantCopy -> planRemoveRedundantCopy(context, request.targetAgentId, targetsByAgentId)

            is SkillSyncRequest.UpdateSharing -> planUpdateSharing(context, request, targetsByAgentId)

            is SkillSyncRequest.PromoteSkill -> error("PromoteSkill is handled before canonical resolution")

            is SkillSyncRequest.ReplaceCopy -> error("ReplaceCopy is handled before canonical resolution")

            is SkillSyncRequest.RepairSkill -> {
                val targetAgentId = request.targetAgentId
                if (targetAgentId == null) {
                    planWholeSkillRepair(context, targetsByAgentId, action)
                } else {
                    planRepairOrResync(context, targetAgentId, targetsByAgentId, action)
                }
            }

            is SkillSyncRequest.ResyncSkill -> planRepairOrResync(context, request.targetAgentId, targetsByAgentId, action)

            is SkillSyncRequest.ResolveConflict -> planResolveConflict(context, request, targetsByAgentId)
        }
    }

    private fun planResolveConflict(
        context: PlanContext,
        request: SkillSyncRequest.ResolveConflict,
        targetsByAgentId: Map<String, SkillSyncTarget>,
    ): SkillSyncPlanResult {
        val targetAgentId = request.targetAgentId

        if (request.resolution == ConflictResolution.CANCEL) {
            return blankResult(context.operationId, context.skillId, context.canonicalPath, SyncAction.RESOLVE_CONFLICT, context.scope, "Cancelled; no changes made.")
        }

        val target = targetsByAgentId[targetAgentId]
            ?: return unsupportedTarget(context, SyncAction.RESOLVE_CONFLICT, targetAgentId)

        val observed = observer.observe(target, context.canonicalPath, context.canonicalFingerprint, context.scope, context.project)
        val targetPath = observed.targetPath
            ?: return unsupportedTarget(context, SyncAction.RESOLVE_CONFLICT, targetAgentId)

        return when (request.resolution) {
            ConflictResolution.CANCEL -> error("CANCEL handled above")
            ConflictResolution.KEEP_CANONICAL -> planKeepCanonical(context, observed)
            ConflictResolution.KEEP_TARGET -> planKeepTarget(context, targetAgentId, targetPath, observed)
            ConflictResolution.KEEP_BOTH -> planKeepBoth(context, request, targetAgentId, targetPath, observed)
        }
    }

    private fun planKeepCanonical(context: PlanContext, observed: ObservedSkillTarget): SkillSyncPlanResult {
        if (context.canonicalFingerprint == null) {
            return blankResult(
                context.operationId,
                context.skillId,
                context.canonicalPath,
                SyncAction.RESOLVE_CONFLICT,
                context.scope,
                "Shared source missing: ${context.canonicalPath}.",
            )
        }
        val planningRequest = planningRequest(context, listOf(observed))
        val steps = planner.buildSteps(observed, planningRequest, backup = context.backupBeforeReplacement, remove = true)
        val plan = SkillSyncPlan(context.operationId, context.skillId, context.canonicalPath, steps, emptyList())
        return SkillSyncPlanResult(plan, planningRequest, SyncAction.RESOLVE_CONFLICT, context.scope)
    }

    private fun planKeepTarget(
        context: PlanContext,
        targetAgentId: String,
        targetPath: Path,
        observed: ObservedSkillTarget,
    ): SkillSyncPlanResult {
        val targetFingerprint = fingerprint.calculate(targetPath)
            ?: return blankResult(
                context.operationId,
                context.skillId,
                context.canonicalPath,
                SyncAction.RESOLVE_CONFLICT,
                context.scope,
                "Could not read $targetAgentId's version to promote it.",
            )

        val steps = buildList {
            if (context.backupBeforeReplacement) add(SkillSyncStep.BackupExisting(targetAgentId, context.canonicalPath))
            add(SkillSyncStep.RemoveExisting(targetAgentId, context.canonicalPath))
            add(SkillSyncStep.CopySkill(targetAgentId, targetPath, context.canonicalPath))
            add(SkillSyncStep.VerifyFingerprint(targetAgentId, context.canonicalPath, targetFingerprint))
        }
        val otherManagedAgentIds = ownershipStore.managedTargetsFor(instanceKey(context))
            .map { it.agentId }
            .filter { it != targetAgentId }
        val warnings = if (otherManagedAgentIds.isEmpty()) {
            emptyList()
        } else {
            listOf(
                SyncWarning(
                    null,
                    "The shared version changed. Resync ${otherManagedAgentIds.joinToString()} separately to update their copies.",
                ),
            )
        }
        val plan = SkillSyncPlan(context.operationId, context.skillId, context.canonicalPath, steps, warnings)
        val planningRequest = planningRequest(context, listOf(observed))
        return SkillSyncPlanResult(plan, planningRequest, SyncAction.RESOLVE_CONFLICT, context.scope)
    }

    // Copies the diverged target's content next to itself under a new name and leaves both the
    // original target and the canonical completely untouched — deliberately not "resolving" the
    // conflict at the original location, since the user chose to keep both, not to pick a winner
    // there. Discovery finds the copy as its own skill on the next scan (a fresh SkillIdentity from
    // its new normalized name), so this never fabricates an identity itself — that stays
    // Milestone 2 discovery-layer scope, per this class's own doc comment; sync only ever moves
    // files.
    private fun planKeepBoth(
        context: PlanContext,
        request: SkillSyncRequest.ResolveConflict,
        targetAgentId: String,
        targetPath: Path,
        observed: ObservedSkillTarget,
    ): SkillSyncPlanResult {
        val newName = request.newDirectoryName
        val newPath = SkillDirectoryName.resolveSibling(targetPath, newName)
        return when {
            newName.isNullOrBlank() ->
                warningResult(context, SyncAction.RESOLVE_CONFLICT, targetAgentId, "Keep Both requires a new name for $targetAgentId's copy.")
            newPath == null ->
                warningResult(context, SyncAction.RESOLVE_CONFLICT, targetAgentId, "The new name must be one directory name without path separators.")
            newPath == targetPath.toAbsolutePath().normalize() ->
                warningResult(context, SyncAction.RESOLVE_CONFLICT, targetAgentId, "The new name must be different from the existing one.")
            Files.exists(newPath) ->
                warningResult(context, SyncAction.RESOLVE_CONFLICT, targetAgentId, "A skill already exists at $newPath; choose a different name.")
            else -> {
                val targetFingerprint = fingerprint.calculate(targetPath)
                if (targetFingerprint == null) {
                    blankResult(context.operationId, context.skillId, context.canonicalPath, SyncAction.RESOLVE_CONFLICT, context.scope, "Could not read $targetAgentId's version to copy.")
                } else {
                    val steps = listOf(
                        SkillSyncStep.CreateDirectory(targetAgentId, newPath.parent),
                        SkillSyncStep.CopySkill(targetAgentId, targetPath, newPath),
                        SkillSyncStep.VerifyFingerprint(targetAgentId, newPath, targetFingerprint),
                    )
                    val warnings = listOf(
                        SyncWarning(null, "$targetAgentId's version was copied to $newPath as an independent skill; refresh discovery to see it."),
                    )
                    val plan = SkillSyncPlan(context.operationId, context.skillId, context.canonicalPath, steps, warnings)
                    val planningRequest = planningRequest(context, listOf(observed))
                    SkillSyncPlanResult(plan, planningRequest, SyncAction.RESOLVE_CONFLICT, context.scope)
                }
            }
        }
    }

    private fun planRepairOrResync(
        context: PlanContext,
        targetAgentId: String,
        targetsByAgentId: Map<String, SkillSyncTarget>,
        action: SyncAction,
    ): SkillSyncPlanResult {
        val target = targetsByAgentId[targetAgentId] ?: return unsupportedTarget(context, action, targetAgentId)

        val key = instanceKey(context)
        val managed = ownershipStore.managedTarget(key, targetAgentId)
            ?: return warningResult(context, action, targetAgentId, "$targetAgentId is not currently managed by AgentHub; nothing to ${action.name.lowercase()}.")

        val observed = observer.observe(target, context.canonicalPath, context.canonicalFingerprint, context.scope, context.project, managed.requestedMode, managed)
        if (!observed.ownershipVerified) {
            return warningResult(context, action, targetAgentId, "$targetAgentId ownership could not be verified; resolve the conflict explicitly.")
        }
        return planResultFromTargets(context, action, listOf(observed))
    }

    /**
     * `RepairSkill` with no single `targetAgentId`: repairs every agent [SyncOwnershipStore] still
     * considers managed for this skill instance, in one plan/preview/execute — like
     * [planShareEverywhere], each agent's steps are independent, so `SkillSyncExecutor` isolates a
     * per-agent failure without affecting the others. An agent that can't be verified as managed
     * gets a warning instead of blocking the rest, exactly like the single-target path above.
     */
    private fun planWholeSkillRepair(
        context: PlanContext,
        targetsByAgentId: Map<String, SkillSyncTarget>,
        action: SyncAction,
    ): SkillSyncPlanResult {
        val key = instanceKey(context)
        val managedAgentIds = ownershipStore.managedTargetsFor(key).map(ManagedTarget::agentId).toSortedSet()
        val warnings = mutableListOf<SyncWarning>()
        val observedTargets = mutableListOf<ObservedSkillTarget>()

        if (managedAgentIds.isEmpty()) {
            warnings += SyncWarning(null, "No agents are currently managed by AgentHub for this skill; nothing to repair.")
        }

        for (agentId in managedAgentIds) {
            val target = targetsByAgentId[agentId]
            val managed = target?.let { ownershipStore.managedTarget(key, agentId) }
            val observed = managed?.let { observer.observe(target, context.canonicalPath, context.canonicalFingerprint, context.scope, context.project, it.requestedMode, it) }
            when {
                target == null -> warnings += SyncWarning(agentId, notSupportedTargetMessage(agentId))
                managed == null -> warnings += SyncWarning(agentId, "$agentId is not currently managed by AgentHub; nothing to repair.")
                observed?.ownershipVerified != true -> warnings += SyncWarning(agentId, "$agentId ownership could not be verified; resolve the conflict explicitly.")
                else -> observedTargets += observed
            }
        }

        val result = planResultFromTargets(context, action, observedTargets)
        return result.copy(plan = result.plan.copy(warnings = result.plan.warnings + warnings))
    }

    private fun planPromote(
        operationId: String,
        request: SkillSyncRequest.PromoteSkill,
        targetsByAgentId: Map<String, SkillSyncTarget>,
        scope: SkillScope,
        project: DiscoveredProject?,
        backupBeforeReplacement: Boolean = true,
    ): SkillSyncPlanResult {
        val skillId = request.skillId
        val sourcePath = request.sourcePath

        val sourceFingerprint = fingerprint.calculate(sourcePath)
            ?: return blankResult(operationId, skillId, sourcePath, SyncAction.PROMOTE, scope, "Source skill not found at $sourcePath.")

        val canonicalRoot = when (scope) {
            SkillScope.GLOBAL -> sharedSkillDirectory.resolveGlobalDirectory()
            SkillScope.PROJECT -> project?.let(sharedSkillDirectory::resolveProjectDirectory)
        } ?: return blankResult(
            operationId,
            skillId,
            sourcePath,
            SyncAction.PROMOTE,
            scope,
            "Could not resolve a shared skill location for this scope.",
        )

        val canonicalPath = canonicalRoot.resolve(sourcePath.fileName)
        if (Files.exists(canonicalPath)) {
            return blankResult(operationId, skillId, canonicalPath, SyncAction.PROMOTE, scope, "A shared skill already exists at $canonicalPath.")
        }

        val target = targetsByAgentId[request.sourceAgentId]
            ?: return blankResult(
                operationId,
                skillId,
                canonicalPath,
                SyncAction.PROMOTE,
                scope,
                "${request.sourceAgentId} is not a supported sync target.",
            )

        val observedSource = observer.observe(target, canonicalPath, sourceFingerprint, scope, project, nativeShortCircuit = false)
        if (observedSource.targetPath != sourcePath || observedSource.status != SkillTargetStatus.IDENTICAL_UNMANAGED) {
            return blankResult(
                operationId,
                skillId,
                canonicalPath,
                SyncAction.PROMOTE,
                scope,
                "Source is not in the expected state to be promoted; refresh and try again.",
            )
        }

        val effectiveMode = when {
            !target.supportsLinkedSkills() -> EffectiveSyncMode.COPY
            request.mode == SkillSyncMode.COPY -> EffectiveSyncMode.COPY
            OsDetector.isWindows() -> EffectiveSyncMode.JUNCTION
            else -> EffectiveSyncMode.SYMLINK
        }
        val agentId = request.sourceAgentId
        val steps = buildList {
            add(SkillSyncStep.CreateDirectory(agentId, canonicalPath.parent))
            add(SkillSyncStep.CopySkill(agentId, sourcePath, canonicalPath))
            add(SkillSyncStep.VerifyFingerprint(agentId, canonicalPath, sourceFingerprint))
            if (backupBeforeReplacement) add(SkillSyncStep.BackupExisting(agentId, sourcePath))
            add(SkillSyncStep.RemoveExisting(agentId, sourcePath))
            if (effectiveMode == EffectiveSyncMode.COPY) {
                add(SkillSyncStep.CopySkill(agentId, canonicalPath, sourcePath))
            } else {
                add(SkillSyncStep.CreateLink(agentId, canonicalPath, sourcePath, request.mode, effectiveMode))
            }
            add(SkillSyncStep.VerifyFingerprint(agentId, sourcePath, sourceFingerprint))
            add(SkillSyncStep.WriteMetadata(agentId, skillId, sourcePath, effectiveMode, request.mode))
        }
        val warnings = buildList {
            if (effectiveMode == EffectiveSyncMode.COPY && request.mode != SkillSyncMode.COPY) {
                add(SyncWarning(agentId, "Linking is unavailable; the promoted source will use a managed copy."))
            }
            if (request.alsoShareWith.isNotEmpty()) {
                add(
                    SyncWarning(
                        null,
                        "Will also share with ${request.alsoShareWith.sorted().joinToString()} once the promotion completes.",
                    ),
                )
            }
        }

        val plan = SkillSyncPlan(operationId, skillId, canonicalPath, steps, warnings)
        val planningRequest = SkillSyncPlanningRequest(
            operationId,
            skillId,
            canonicalPath,
            sourceFingerprint,
            listOf(observedSource),
            observedCanonicalFingerprint = null,
            instanceKey = resolveInstanceKey(skillId, scope, project, canonicalPath, runtimeId),
            nativeShortCircuit = false,
        )
        return SkillSyncPlanResult(plan, planningRequest, SyncAction.PROMOTE, scope)
    }

    private fun planShare(
        context: PlanContext,
        targetAgentId: String,
        mode: SkillSyncMode,
        targetsByAgentId: Map<String, SkillSyncTarget>,
    ): SkillSyncPlanResult {
        val target = targetsByAgentId[targetAgentId] ?: return unsupportedTarget(context, SyncAction.SHARE, targetAgentId)

        val managed = ownershipStore.managedTarget(instanceKey(context), targetAgentId)
        val observed = observer.observe(target, context.canonicalPath, context.canonicalFingerprint, context.scope, context.project, mode, managed)
        return planResultFromTargets(context, SyncAction.SHARE, listOf(observed))
    }

    private fun planShareEverywhere(
        context: PlanContext,
        mode: SkillSyncMode,
        targetsByAgentId: Map<String, SkillSyncTarget>,
        installedAgentIds: Set<String>,
    ): SkillSyncPlanResult {
        // supportsSkills, not supportsSharedAgentSkills: sync targets a per-agent directory
        // (e.g. .claude/skills), so what matters is whether the agent has Agent Skills support at
        // all, not whether it natively reads the shared .agents/skills convention directly.
        // Eligibility must match the single-target "Share with…" set (installed + supportsSkills),
        // not just targetsByAgentId.keys — otherwise agents without a SkillSyncTarget adapter yet
        // silently vanish from Share Everywhere instead of getting the same warning a single Share
        // to that agent would produce.
        val eligibleAgentIds = installedAgentIds
            .filter { AgentCapabilityRegistry.capabilitiesFor(it).supportsSkills }
            .toSet()
        val (withAdapter, withoutAdapter) = eligibleAgentIds.partition { it in targetsByAgentId }

        val key = instanceKey(context)
        val observed = withAdapter.map { agentId ->
            val managed = ownershipStore.managedTarget(key, agentId)
            observer.observe(targetsByAgentId.getValue(agentId), context.canonicalPath, context.canonicalFingerprint, context.scope, context.project, mode, managed)
        }
        val result = planResultFromTargets(context, SyncAction.SHARE_EVERYWHERE, observed)
        return withoutAdapter.sorted().fold(result) { acc, agentId ->
            acc.withWarning(agentId, notSupportedTargetMessage(agentId))
        }
    }

    /**
     * "Share with…" as a set: shares with the newly checked agents and stops sharing with the newly
     * unchecked ones in a single plan. Every agent's steps are independent (the executor groups by
     * agent and isolates a per-agent failure), so concatenating the per-agent plans is safe; the
     * two sets are disjoint by construction, and an agent named in both is only shared.
     */
    private fun planUpdateSharing(
        context: PlanContext,
        request: SkillSyncRequest.UpdateSharing,
        targetsByAgentId: Map<String, SkillSyncTarget>,
    ): SkillSyncPlanResult {
        val stopAgentIds = (request.stopAgentIds - request.shareAgentIds).toSortedSet()
        val parts = buildList {
            if (request.shareAgentIds.isNotEmpty()) {
                add(planShareEverywhere(context, request.mode, targetsByAgentId, request.shareAgentIds))
            }
            stopAgentIds.forEach { add(planStopSharing(context, it, targetsByAgentId)) }
        }
        if (parts.isEmpty()) {
            return blankResult(context.operationId, context.skillId, context.canonicalPath, SyncAction.UPDATE_SHARING, context.scope, "No agents were chosen; nothing to change.")
        }
        val plan = SkillSyncPlan(
            context.operationId,
            context.skillId,
            context.canonicalPath,
            parts.flatMap { it.plan.steps },
            parts.flatMap { it.plan.warnings },
        )
        val planningRequest = planningRequest(context, parts.flatMap { it.planningRequest.targets })
        return SkillSyncPlanResult(plan, planningRequest, SyncAction.UPDATE_SHARING, context.scope)
    }

    private fun planStopSharing(
        context: PlanContext,
        targetAgentId: String,
        targetsByAgentId: Map<String, SkillSyncTarget>,
    ): SkillSyncPlanResult {
        val target = targetsByAgentId[targetAgentId] ?: return unsupportedTarget(context, SyncAction.STOP_SHARING, targetAgentId)

        val key = instanceKey(context)
        val managed = ownershipStore.managedTarget(key, targetAgentId)
        val observed = observer.observe(
            target,
            context.canonicalPath,
            context.canonicalFingerprint,
            context.scope,
            context.project,
            managedTarget = managed,
        )
        val planningRequest = planningRequest(context, listOf(observed))

        if (observed.status == SkillTargetStatus.NATIVE) {
            val plan = SkillSyncPlan(
                operationId = context.operationId,
                skillId = context.skillId,
                canonicalPath = context.canonicalPath,
                steps = emptyList(),
                warnings = listOf(
                    SyncWarning(targetAgentId, "$targetAgentId reads the shared source directly; there is nothing to stop sharing."),
                ),
            )
            return SkillSyncPlanResult(plan, planningRequest, SyncAction.STOP_SHARING, context.scope)
        }

        val managedStoppable = observed.ownershipVerified &&
            observed.status in setOf(SkillTargetStatus.LINKED, SkillTargetStatus.COPIED) &&
            observed.targetPath != null
        // Sharing AgentHub did not create: a link to the shared source or a copy identical to it.
        // Removing either loses nothing (the content is at the shared source), so with "manage
        // existing" on it is allowed — but always behind a backup, and never for diverged content.
        val existingLinkRepresentation = observed.targetPath
            ?.takeIf { observed.status == SkillTargetStatus.LINKED }
            ?.let(::existingLinkRepresentation)
        val existingStoppable = context.manageExistingTargets && !observed.ownershipVerified && observed.targetPath != null &&
            (
                (observed.status == SkillTargetStatus.LINKED && existingLinkRepresentation != null) ||
                    observed.status == SkillTargetStatus.IDENTICAL_UNMANAGED
                )

        val plan = if (managedStoppable || existingStoppable) {
            val path = requireNotNull(observed.targetPath)
            SkillSyncPlan(
                operationId = context.operationId,
                skillId = context.skillId,
                canonicalPath = context.canonicalPath,
                steps = buildList {
                    if (existingStoppable) {
                        val isLink = observed.status == SkillTargetStatus.LINKED
                        add(
                            SkillSyncStep.BackupExisting(
                                targetAgentId,
                                path,
                                representation = if (isLink) existingLinkRepresentation else EffectiveSyncMode.COPY,
                                linkTarget = context.canonicalPath.takeIf { isLink },
                            ),
                        )
                    } else if (context.backupBeforeReplacement) {
                        add(
                            SkillSyncStep.BackupExisting(
                                targetAgentId,
                                path,
                                representation = managed?.effectiveMode,
                                linkTarget = managed?.takeIf { it.effectiveMode != EffectiveSyncMode.COPY }?.let { context.canonicalPath },
                            ),
                        )
                    }
                    add(SkillSyncStep.RemoveExisting(targetAgentId, path))
                },
                warnings = if (existingStoppable) {
                    listOf(
                        SyncWarning(
                            targetAgentId,
                            "This ${if (observed.status == SkillTargetStatus.LINKED) "link" else "copy"} was not created by AgentHub; " +
                                "it is removed after a backup because \"Manage existing skills\" is on.",
                        ),
                    )
                } else {
                    emptyList()
                },
            )
        } else {
            val couldBeManaged = observed.status in setOf(SkillTargetStatus.LINKED, SkillTargetStatus.IDENTICAL_UNMANAGED)
            SkillSyncPlan(
                operationId = context.operationId,
                skillId = context.skillId,
                canonicalPath = context.canonicalPath,
                steps = emptyList(),
                warnings = listOf(
                    SyncWarning(
                        targetAgentId,
                        "Not currently a managed link or copy; stopping sharing isn't available for unmanaged content." +
                            if (couldBeManaged && !context.manageExistingTargets) {
                                " Turn on \"Manage existing skills\" in the Skill Sync settings to allow it for links and identical copies."
                            } else {
                                ""
                            },
                    ),
                ),
            )
        }

        return SkillSyncPlanResult(plan, planningRequest, SyncAction.STOP_SHARING, context.scope)
    }

    /**
     * Drops an agent's own link or copy of a skill it already reads from the shared folder. Unlike
     * [planStopSharing] this observes the agent's real directory (no NATIVE short-circuit), and the
     * button the user pressed is the consent, so it does not depend on "Manage existing skills" -
     * removing a link to, or an identical copy of, the shared skill loses nothing, and it is always
     * behind a backup. Diverged content, and a folder another agent's skills also live in, are left alone.
     */
    private fun planRemoveRedundantCopy(
        context: PlanContext,
        targetAgentId: String,
        targetsByAgentId: Map<String, SkillSyncTarget>,
    ): SkillSyncPlanResult {
        val action = SyncAction.REMOVE_REDUNDANT_COPY
        val target = targetsByAgentId[targetAgentId] ?: return unsupportedTarget(context, action, targetAgentId)
        if (!AgentCapabilityRegistry.capabilitiesFor(targetAgentId).supportsSharedAgentSkills) {
            return warningResult(context, action, targetAgentId, "$targetAgentId does not read the shared skills folder, so its own copy is not redundant.")
        }

        val key = instanceKey(context)
        val managed = ownershipStore.managedTarget(key, targetAgentId)
        val observed = observer.observe(
            target,
            context.canonicalPath,
            context.canonicalFingerprint,
            context.scope,
            context.project,
            managedTarget = managed,
            nativeShortCircuit = false,
        )
        val planningRequest = SkillSyncPlanningRequest(
            context.operationId,
            context.skillId,
            context.canonicalPath,
            context.canonicalFingerprint,
            listOf(observed),
            instanceKey = key,
            backupBeforeReplacement = context.backupBeforeReplacement,
            nativeShortCircuit = false,
        )

        val path = observed.targetPath
        val ownRoot = when (context.scope) {
            SkillScope.GLOBAL -> target.globalSkillDirectory()
            SkillScope.PROJECT -> context.project?.let(target::projectSkillDirectory)
        }
        val inOwnDirectory = path != null && ownRoot != null &&
            runCatching { path.toAbsolutePath().normalize().parent == ownRoot.toAbsolutePath().normalize() }.getOrDefault(false)
        val isLink = observed.status == SkillTargetStatus.LINKED
        val linkRepresentation = if (isLink && path != null) {
            managed?.takeIf { observed.ownershipVerified }?.effectiveMode ?: existingLinkRepresentation(path)
        } else {
            null
        }
        val removable = path != null && inOwnDirectory && when (observed.status) {
            SkillTargetStatus.LINKED -> linkRepresentation != null
            SkillTargetStatus.COPIED, SkillTargetStatus.IDENTICAL_UNMANAGED -> true
            else -> false
        }

        val plan = if (removable) {
            val existing = requireNotNull(path)
            SkillSyncPlan(
                context.operationId,
                context.skillId,
                context.canonicalPath,
                listOf(
                    SkillSyncStep.BackupExisting(
                        targetAgentId,
                        existing,
                        representation = if (isLink) linkRepresentation else EffectiveSyncMode.COPY,
                        linkTarget = context.canonicalPath.takeIf { isLink },
                    ),
                    SkillSyncStep.RemoveExisting(targetAgentId, existing),
                ),
                listOf(
                    SyncWarning(
                        targetAgentId,
                        if (isLink) {
                            "$targetAgentId reads the shared folder directly, so its link at $existing is redundant; only the link " +
                                "is removed (after a backup), never the shared skill it points to."
                        } else {
                            "$targetAgentId reads the shared folder directly, so its own copy at $existing is redundant; it is " +
                                "removed after a backup."
                        },
                    ),
                ),
            )
        } else {
            val reason = when {
                path != null && !inOwnDirectory -> "it lives in a folder other agents' skills share, so it is left alone"
                observed.status == SkillTargetStatus.DIFFERENT -> "its content differs from the shared skill, so it is kept"
                else -> "nothing redundant was found"
            }
            SkillSyncPlan(
                context.operationId,
                context.skillId,
                context.canonicalPath,
                emptyList(),
                listOf(SyncWarning(targetAgentId, "Nothing was removed for $targetAgentId: $reason.")),
            )
        }
        return SkillSyncPlanResult(plan, planningRequest, action, context.scope)
    }

    private fun planResultFromTargets(
        context: PlanContext,
        action: SyncAction,
        targets: List<ObservedSkillTarget>,
    ): SkillSyncPlanResult {
        val planningRequest = planningRequest(context, targets)
        return SkillSyncPlanResult(planner.plan(planningRequest), planningRequest, action, context.scope)
    }

    private fun planningRequest(context: PlanContext, targets: List<ObservedSkillTarget>) = SkillSyncPlanningRequest(
        context.operationId,
        context.skillId,
        context.canonicalPath,
        context.canonicalFingerprint,
        targets,
        instanceKey = instanceKey(context),
        backupBeforeReplacement = context.backupBeforeReplacement,
    )

    private fun instanceKey(context: PlanContext) =
        resolveInstanceKey(context.skillId, context.scope, context.project, context.canonicalPath, runtimeId)

    /** The single place that builds a warning-only, no-steps plan result — every early-return path funnels through this. */
    private fun warningResult(context: PlanContext, action: SyncAction, agentId: String?, message: String): SkillSyncPlanResult =
        planResultFromTargets(context, action, emptyList()).withWarning(agentId, message)

    private fun unsupportedTarget(context: PlanContext, action: SyncAction, agentId: String): SkillSyncPlanResult =
        warningResult(context, action, agentId, notSupportedTargetMessage(agentId))

    private fun notSupportedTargetMessage(agentId: String) = "$agentId is not a supported sync target."

    /**
     * How a link AgentHub has no record of is stored, for the backup that lets Undo recreate it: a
     * real symlink, or — Windows only — a junction. Any other directory that merely resolves to the
     * shared source can't be recreated faithfully, so it stays protected.
     */
    private fun existingLinkRepresentation(path: Path): EffectiveSyncMode? = when {
        Files.isSymbolicLink(path) -> EffectiveSyncMode.SYMLINK
        OsDetector.isWindows() -> EffectiveSyncMode.JUNCTION
        else -> null
    }

    private fun SkillSyncPlanResult.withWarning(agentId: String?, message: String): SkillSyncPlanResult =
        copy(plan = plan.copy(warnings = plan.warnings + SyncWarning(agentId, message)))

    private fun emptyResult(
        operationId: String,
        skillId: String,
        skill: AgentSkill,
        action: SyncAction,
        scope: SkillScope,
        message: String,
    ): SkillSyncPlanResult {
        val canonicalPath = skill.sources.firstOrNull { it.shared }?.path?.let(Path::of)
            ?: skill.sources.firstOrNull()?.path?.let(Path::of)
            ?: Path.of(".")
        return blankResult(operationId, skillId, canonicalPath, action, scope, message)
    }

    private fun blankResult(
        operationId: String,
        skillId: String,
        referencePath: Path,
        action: SyncAction,
        scope: SkillScope,
        message: String,
    ): SkillSyncPlanResult {
        val plan = SkillSyncPlan(
            operationId = operationId,
            skillId = skillId,
            canonicalPath = referencePath,
            steps = emptyList(),
            warnings = listOf(SyncWarning(null, message)),
        )
        val planningRequest = SkillSyncPlanningRequest(operationId, skillId, referencePath, null, emptyList())
        return SkillSyncPlanResult(plan, planningRequest, action, scope)
    }

    private fun SkillSyncRequest.toAction(): SyncAction = when (this) {
        is SkillSyncRequest.PromoteSkill -> SyncAction.PROMOTE
        is SkillSyncRequest.ReplaceCopy -> SyncAction.REPLACE_COPY
        is SkillSyncRequest.RemoveRedundantCopy -> SyncAction.REMOVE_REDUNDANT_COPY
        is SkillSyncRequest.ShareSkill -> SyncAction.SHARE
        is SkillSyncRequest.ShareSkillEverywhere -> SyncAction.SHARE_EVERYWHERE
        is SkillSyncRequest.StopSharing -> SyncAction.STOP_SHARING
        is SkillSyncRequest.UpdateSharing -> SyncAction.UPDATE_SHARING
        is SkillSyncRequest.RepairSkill -> SyncAction.REPAIR
        is SkillSyncRequest.ResyncSkill -> SyncAction.RESYNC
        is SkillSyncRequest.ResolveConflict -> SyncAction.RESOLVE_CONFLICT
    }

    /**
     * Overwrites [SkillSyncRequest.ReplaceCopy.targetPath] with a copy of the source version, always
     * behind a backup. Like a promotion it works from an agent's own directory, so an agent that also
     * reads the shared folder natively is still observed as an ordinary copy.
     */
    private fun planReplaceCopy(
        operationId: String,
        request: SkillSyncRequest.ReplaceCopy,
        targetsByAgentId: Map<String, SkillSyncTarget>,
        scope: SkillScope,
        project: DiscoveredProject?,
    ): SkillSyncPlanResult {
        val skillId = request.skillId
        val action = SyncAction.REPLACE_COPY
        val sourceFingerprint = fingerprint.calculate(request.sourcePath)
            ?: return blankResult(operationId, skillId, request.sourcePath, action, scope, "Source skill not found at ${request.sourcePath}.")
        val target = targetsByAgentId[request.targetAgentId]
            ?: return blankResult(operationId, skillId, request.targetPath, action, scope, "${request.targetAgentId} is not a supported sync target.")
        val targetFingerprint = fingerprint.calculate(request.targetPath)
            ?: return blankResult(operationId, skillId, request.targetPath, action, scope, "The copy to replace was not found at ${request.targetPath}.")
        if (targetFingerprint == sourceFingerprint) {
            return blankResult(operationId, skillId, request.targetPath, action, scope, "Both copies are already identical.")
        }
        val observed = observer.observe(target, request.sourcePath, sourceFingerprint, scope, project, nativeShortCircuit = false)
        if (observed.targetPath != request.targetPath) {
            return blankResult(
                operationId,
                skillId,
                request.targetPath,
                action,
                scope,
                "The copy is not where ${request.targetAgentId} keeps this skill; refresh and try again.",
            )
        }
        val agentId = request.targetAgentId
        val steps = listOf(
            SkillSyncStep.BackupExisting(agentId, request.targetPath),
            SkillSyncStep.RemoveExisting(agentId, request.targetPath),
            SkillSyncStep.CopySkill(agentId, request.sourcePath, request.targetPath),
            SkillSyncStep.VerifyFingerprint(agentId, request.targetPath, sourceFingerprint),
        )
        val plan = SkillSyncPlan(
            operationId,
            skillId,
            request.sourcePath,
            steps,
            listOf(SyncWarning(agentId, "The current copy is backed up, then replaced by a plain copy of ${request.sourcePath}.")),
        )
        val planningRequest = SkillSyncPlanningRequest(
            operationId,
            skillId,
            request.sourcePath,
            sourceFingerprint,
            listOf(observed),
            instanceKey = resolveInstanceKey(skillId, scope, project, request.sourcePath, runtimeId),
            nativeShortCircuit = false,
        )
        return SkillSyncPlanResult(plan, planningRequest, action, scope)
    }
}
