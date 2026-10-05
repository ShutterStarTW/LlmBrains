package com.shutterstar.agenthub.environment.skills.sync.planning

import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncPlan
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStep
import com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SyncWarning

/**
 * Pure and deterministic: given a [SkillSyncPlanningRequest] (already-observed target states, no
 * further filesystem access), produces the [SkillSyncPlan] to preview/execute. Never touches the
 * filesystem, never calls [com.shutterstar.agenthub.environment.skills.sync.link.FileLinkStrategy],
 * never invents an operation id — all inputs are supplied by the caller so this stays trivially
 * unit-testable.
 */
class SkillSyncPlanner {
    fun plan(request: SkillSyncPlanningRequest): SkillSyncPlan {
        if (request.canonicalFingerprint == null) {
            return SkillSyncPlan(
                operationId = request.operationId,
                skillId = request.skillId,
                canonicalPath = request.canonicalPath,
                steps = emptyList(),
                warnings = listOf(SyncWarning(agentId = null, message = "Shared source missing: ${request.canonicalPath}")),
            )
        }

        val steps = mutableListOf<SkillSyncStep>()
        val warnings = mutableListOf<SyncWarning>()

        for (target in request.targets) {
            if (target.status in ACTIONABLE_STATUSES &&
                target.requestedMode == SkillSyncMode.SYMLINK &&
                target.availableLinkMode == null
            ) {
                warnings += SyncWarning(target.agentId, "Linking is unavailable; this target will use a managed copy.")
            }
            when (target.status) {
                SkillTargetStatus.LINKED, SkillTargetStatus.NATIVE -> Unit
                SkillTargetStatus.UNSUPPORTED ->
                    warnings += SyncWarning(target.agentId, "${target.agentId} does not support Agent Skills.")
                SkillTargetStatus.ERROR ->
                    warnings += SyncWarning(target.agentId, "Could not determine state for ${target.agentId}.")
                SkillTargetStatus.MISSING_SOURCE ->
                    warnings += SyncWarning(target.agentId, "Shared source missing for ${target.agentId}.")
                SkillTargetStatus.DIFFERENT ->
                    warnings += SyncWarning(target.agentId, "${target.agentId} has a different version.")
                SkillTargetStatus.NOT_AVAILABLE ->
                    steps += buildSteps(target, request, backup = false, remove = false)
                SkillTargetStatus.BROKEN_LINK -> if (target.ownershipVerified) {
                    steps += buildSteps(target, request, backup = request.backupBeforeReplacement, remove = true)
                } else {
                    warnings += SyncWarning(target.agentId, "${target.agentId} has an unmanaged broken link; explicit takeover is required.")
                }
                SkillTargetStatus.IDENTICAL_UNMANAGED, SkillTargetStatus.COPIED ->
                    steps += buildSteps(target, request, backup = request.backupBeforeReplacement, remove = true)
            }
        }

        return SkillSyncPlan(
            operationId = request.operationId,
            skillId = request.skillId,
            canonicalPath = request.canonicalPath,
            steps = steps,
            warnings = warnings,
        )
    }

    /**
     * Purely mechanical — reads only [target]'s path/mode fields and [request]'s canonical
     * path/fingerprint, never branches on [target]'s status. `internal` (not `private`) so
     * [com.shutterstar.agenthub.environment.skills.sync.SkillSyncEngine] can reuse the exact same
     * step shape for `ResolveConflict`'s `KEEP_CANONICAL` resolution, which deliberately bypasses
     * [plan]'s own DIFFERENT-is-untouchable policy (an explicit, user-authorized exception to it,
     * not a change to that policy).
     */
    internal fun buildSteps(
        target: ObservedSkillTarget,
        request: SkillSyncPlanningRequest,
        backup: Boolean,
        remove: Boolean,
    ): List<SkillSyncStep> {
        val targetPath = requireNotNull(target.targetPath) { "targetPath must be resolved for status ${target.status}" }
        val agentId = target.agentId
        val steps = mutableListOf<SkillSyncStep>()

        steps += SkillSyncStep.CreateDirectory(agentId, targetPath.parent)
        if (backup) {
            steps += SkillSyncStep.BackupExisting(
                agentId,
                targetPath,
                representation = target.managedMode,
                linkTarget = target.managedLinkTarget,
            )
        }
        if (backup || remove) steps += SkillSyncStep.RemoveExisting(agentId, targetPath)

        val effectiveMode = if (target.requestedMode == SkillSyncMode.SYMLINK && target.availableLinkMode != null) {
            target.availableLinkMode
        } else {
            EffectiveSyncMode.COPY
        }

        steps += if (effectiveMode == EffectiveSyncMode.COPY) {
            SkillSyncStep.CopySkill(agentId, request.canonicalPath, targetPath)
        } else {
            SkillSyncStep.CreateLink(agentId, request.canonicalPath, targetPath, target.requestedMode, effectiveMode)
        }

        steps += SkillSyncStep.VerifyFingerprint(agentId, targetPath, request.canonicalFingerprint!!)
        steps += SkillSyncStep.WriteMetadata(agentId, request.skillId, targetPath, effectiveMode, target.requestedMode)

        return steps
    }

    private companion object {
        val ACTIONABLE_STATUSES = setOf(
            SkillTargetStatus.NOT_AVAILABLE,
            SkillTargetStatus.BROKEN_LINK,
            SkillTargetStatus.IDENTICAL_UNMANAGED,
            SkillTargetStatus.COPIED,
        )
    }
}
