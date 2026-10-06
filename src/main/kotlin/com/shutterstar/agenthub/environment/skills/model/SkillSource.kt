package com.shutterstar.agenthub.environment.skills.model

data class SkillSource(
    val agentId: String?,
    val path: String,
    val scope: SkillScope,
    val shared: Boolean,
    val fingerprint: String,
    val displayTitle: String? = null,
    val projectName: String? = null,
    /** Vendor-shipped or account-synced rather than something the user created or shared themselves. */
    val system: Boolean = false,
    /** Resolved during discovery so the UI can collapse aliases without filesystem access on the EDT. */
    val realPath: String? = null,
) {
    /** False when [agentId] only reads this folder because it is another agent's (e.g. Cline reading `.claude/skills`). */
    val native: Boolean get() = agentId == null || SkillRootOwner.isNative(agentId, path)
}
