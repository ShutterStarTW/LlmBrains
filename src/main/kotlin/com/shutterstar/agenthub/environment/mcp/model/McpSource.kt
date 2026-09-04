package com.shutterstar.agenthub.environment.mcp.model

data class McpSource(
    val agentId: String,
    val configPath: String,
    val sourceName: String,
    val projectName: String? = null,
)
