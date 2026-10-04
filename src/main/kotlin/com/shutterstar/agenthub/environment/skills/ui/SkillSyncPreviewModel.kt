package com.shutterstar.agenthub.environment.skills.ui

import com.shutterstar.agenthub.environment.skills.sync.PreparedSkillSync
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAction
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStep
import com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus
import com.shutterstar.agenthub.environment.skills.model.SkillScope

internal data class SkillSyncPreviewTarget(
    val agentId: String,
    val agentName: String,
    val currentState: String,
    val plannedChange: String,
    val targetPath: String?,
    val requestedMode: String?,
    val effectiveMode: String?,
    val willChange: Boolean,
)

internal data class SkillSyncPreviewModel(
    val title: String,
    val context: String,
    val canonicalPath: String,
    val backupPath: String?,
    val targets: List<SkillSyncPreviewTarget>,
    val warnings: List<String>,
) {
    val changeCount: Int get() = targets.count(SkillSyncPreviewTarget::willChange)
    val canApply: Boolean get() = changeCount > 0

    companion object {
        fun from(prepared: PreparedSkillSync, displayName: (String) -> String): SkillSyncPreviewModel {
            val result = prepared.planResult
            val stepsByAgent = result.plan.steps.groupBy(SkillSyncStep::agentId)
            val observations = result.planningRequest.targets.associateBy { it.agentId }
            val agentIds = (observations.keys + stepsByAgent.keys).sorted()
            val targets = agentIds.map { agentId ->
                val observed = observations[agentId]
                val steps = stepsByAgent[agentId].orEmpty()
                val metadata = steps.filterIsInstance<SkillSyncStep.WriteMetadata>().lastOrNull()
                val link = steps.filterIsInstance<SkillSyncStep.CreateLink>().lastOrNull()
                val effectiveMode = metadata?.mode ?: link?.effectiveMode
                SkillSyncPreviewTarget(
                    agentId = agentId,
                    agentName = displayName(agentId),
                    currentState = observed?.status?.label() ?: "Not available",
                    plannedChange = plannedChange(result.action, steps, effectiveMode),
                    targetPath = observed?.targetPath?.toString()
                        ?: metadata?.target?.toString()
                        ?: link?.target?.toString(),
                    requestedMode = (metadata?.requestedMode ?: link?.requestedMode ?: observed?.requestedMode)
                        ?.name?.lowercase()?.replaceFirstChar { it.uppercase() },
                    effectiveMode = effectiveMode?.label(),
                    willChange = steps.isNotEmpty(),
                )
            }
            val context = when (result.scope) {
                SkillScope.GLOBAL -> "Global · Host"
                SkillScope.PROJECT ->
                    "${prepared.project?.name ?: "Project"} · Project · Host"
            }
            return SkillSyncPreviewModel(
                title = "${result.action.label()} ${result.planningRequest.skillId}",
                context = context,
                canonicalPath = result.plan.canonicalPath.toString(),
                backupPath = prepared.backupRoot.takeIf {
                    result.plan.steps.any { step -> step is SkillSyncStep.BackupExisting }
                }?.resolve(result.plan.operationId)?.toString(),
                targets = targets,
                warnings = result.plan.warnings.map { it.message },
            )
        }

        private fun plannedChange(
            action: SyncAction,
            steps: List<SkillSyncStep>,
            mode: EffectiveSyncMode?,
        ): String {
            if (steps.isEmpty()) return "No file changes"
            val backup = steps.any { it is SkillSyncStep.BackupExisting }
            val prefix = if (backup) "Back up existing source → " else ""
            return when {
                action == SyncAction.PROMOTE -> "$prefix create shared source → replace agent source with ${mode?.label() ?: "managed copy"}"
                steps.any { it is SkillSyncStep.CreateLink } -> "$prefix create ${mode?.label() ?: "link"}"
                steps.any { it is SkillSyncStep.CopySkill } -> "$prefix copy shared skill"
                // Stop sharing (also the unchecked agents of "Share with…"): nothing is created, the
                // AgentHub-managed link or copy is removed.
                steps.any { it is SkillSyncStep.RemoveExisting } -> "${prefix}remove the AgentHub-managed link or copy"
                else -> "Update AgentHub synchronization metadata"
            }
        }

        private fun SkillTargetStatus.label(): String = when (this) {
            SkillTargetStatus.NOT_AVAILABLE -> "Not available"
            SkillTargetStatus.NATIVE -> "Native · reads shared source"
            SkillTargetStatus.LINKED -> "Linked · AgentHub"
            SkillTargetStatus.COPIED -> "Copied · AgentHub"
            SkillTargetStatus.IDENTICAL_UNMANAGED -> "Identical · Unmanaged"
            SkillTargetStatus.DIFFERENT -> "Conflict"
            SkillTargetStatus.BROKEN_LINK -> "Broken link"
            SkillTargetStatus.MISSING_SOURCE -> "Shared source missing"
            SkillTargetStatus.UNSUPPORTED -> "Sync unavailable"
            SkillTargetStatus.ERROR -> "Could not inspect"
        }

        private fun EffectiveSyncMode.label(): String = when (this) {
            EffectiveSyncMode.SYMLINK -> "linked"
            EffectiveSyncMode.JUNCTION -> "linked (junction)"
            EffectiveSyncMode.COPY -> "managed copy"
        }
    }
}
