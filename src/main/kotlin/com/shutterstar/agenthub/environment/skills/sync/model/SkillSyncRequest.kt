package com.shutterstar.agenthub.environment.skills.sync.model

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import java.nio.file.Path

/**
 * Pure request shapes accepted by `SkillSyncService.plan()`. No IntelliJ dependencies, so
 * planning and execution tests remain lightweight.
 */
sealed interface SkillSyncRequest {
    val skillId: String

    data class PromoteSkill(
        override val skillId: String,
        val sourceAgentId: String,
        val sourcePath: Path,
        val scope: SkillScope,
        val alsoShareWith: Set<String> = emptySet(),
        val mode: SkillSyncMode = SkillSyncMode.SYMLINK,
    ) : SkillSyncRequest

    /**
     * Overwrites one agent's copy of a skill ([targetPath], kept by [targetAgentId]) with the content
     * of another version ([sourcePath]) - for two copies that differ, whether or not a shared skill
     * exists. The target is always backed up first, so the operation can be undone. The result is a
     * plain copy, not an AgentHub-managed one.
     */
    data class ReplaceCopy(
        override val skillId: String,
        val sourcePath: Path,
        val targetAgentId: String,
        val targetPath: Path,
        val scope: SkillScope,
    ) : SkillSyncRequest

    data class ShareSkill(
        override val skillId: String,
        val targetAgentId: String,
        val mode: SkillSyncMode = SkillSyncMode.SYMLINK,
    ) : SkillSyncRequest

    data class ShareSkillEverywhere(
        override val skillId: String,
        val mode: SkillSyncMode = SkillSyncMode.SYMLINK,
    ) : SkillSyncRequest

    /**
     * The "Share with…" checklist as ONE operation: share with [shareAgentIds] and stop sharing with
     * [stopAgentIds] (agents that were shared and got unchecked) — one plan, one preview, one audit
     * entry, one Undo. An agent in both sets is only shared.
     */
    data class UpdateSharing(
        override val skillId: String,
        val shareAgentIds: Set<String>,
        val stopAgentIds: Set<String>,
        val mode: SkillSyncMode = SkillSyncMode.SYMLINK,
    ) : SkillSyncRequest

    data class StopSharing(
        override val skillId: String,
        val targetAgentId: String,
    ) : SkillSyncRequest

    /**
     * Removes [targetAgentId]'s own link or copy of a skill that the agent already reads from the shared
     * folder, so only the shared one is left. Only a link to the shared skill or a copy identical to it is
     * removed (always behind a backup); diverged content is never touched.
     */
    data class RemoveRedundantCopy(
        override val skillId: String,
        val targetAgentId: String,
    ) : SkillSyncRequest

    data class RepairSkill(
        override val skillId: String,
        val targetAgentId: String? = null,
    ) : SkillSyncRequest

    data class ResyncSkill(
        override val skillId: String,
        val targetAgentId: String,
    ) : SkillSyncRequest

    data class ResolveConflict(
        override val skillId: String,
        val targetAgentId: String,
        val resolution: ConflictResolution,
        /** Only consulted for [ConflictResolution.KEEP_BOTH]: the new leaf directory name for the diverged copy, created as a sibling of the conflicting target — never the original name, so it can never collide with it. */
        val newDirectoryName: String? = null,
    ) : SkillSyncRequest
}

enum class ConflictResolution {
    KEEP_CANONICAL,
    KEEP_TARGET,
    KEEP_BOTH,
    CANCEL,
}
