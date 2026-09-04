package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.model.RawAgentProject

interface AgentProjectProvider {
    val agentId: String

    fun isAvailable(): Boolean

    fun discover(): List<RawAgentProject>
}
