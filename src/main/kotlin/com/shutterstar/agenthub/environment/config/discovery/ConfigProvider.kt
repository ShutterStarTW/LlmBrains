package com.shutterstar.agenthub.environment.config.discovery

import com.shutterstar.agenthub.environment.config.model.AgentConfigSource
import com.shutterstar.agenthub.projects.model.DiscoveredProject

interface ConfigProvider {
    val agentId: String
    fun discoverGlobal(): List<AgentConfigSource>
    fun discoverProject(project: DiscoveredProject): List<AgentConfigSource>
}
