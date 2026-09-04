package com.shutterstar.agenthub.environment.skills.model

data class AgentSkill(
    val identity: SkillIdentity,
    val name: String,
    val description: String?,
    val scope: SkillScope,
    val sources: List<SkillSource>,
    val compatibleAgents: Set<String>,
    val consistency: SkillConsistency,
)
