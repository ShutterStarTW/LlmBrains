package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.projects.model.DiscoveredProject

interface McpProvider {
    val agentId: String

    fun discoverGlobal(): List<RawMcpServer>

    fun discoverProject(project: DiscoveredProject): List<RawMcpServer>
}

data class RawMcpServer(
    val agentId: String,
    val name: String,
    val transport: McpTransport,
    val command: String?,
    val args: List<String>,
    val url: String?,
    val environmentVariableNames: Set<String>,
    val configPath: String,
    val scope: McpScope,
    val projectName: String? = null,
    val environmentFile: String? = null,
    val privateConfigurationFingerprint: String? = null,
)
