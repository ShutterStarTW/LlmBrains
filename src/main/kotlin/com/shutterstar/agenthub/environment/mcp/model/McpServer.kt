package com.shutterstar.agenthub.environment.mcp.model

data class McpServer(
    val id: String,
    val name: String,
    val transport: McpTransport,
    val command: String?,
    val args: List<String>,
    val url: String?,
    val environmentVariableNames: Set<String>,
    val sources: List<McpSource>,
    val scope: McpScope,
    val consistency: McpConsistency,
)
