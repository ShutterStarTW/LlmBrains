package com.shutterstar.agenthub.environment.skills.model

data class SkillSource(
    val agentId: String?,
    val path: String,
    val scope: SkillScope,
    val shared: Boolean,
    val fingerprint: String,
    val displayTitle: String? = null,
    val projectName: String? = null,
)
