package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject

interface SkillProvider {
    val agentId: String?

    fun discoverGlobal(): List<SkillSourceRecord>

    fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord>
}

data class SkillSourceRecord(
    val name: String,
    val path: String,
    val description: String?,
    val agentId: String?,
    val scope: SkillScope,
    val shared: Boolean,
    val fingerprint: String,
    val displayTitle: String? = null,
    val projectName: String? = null,
)
