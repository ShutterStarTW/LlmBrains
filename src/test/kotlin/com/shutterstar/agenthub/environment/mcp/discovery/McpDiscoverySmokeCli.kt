package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import java.nio.file.Path

fun main() {
    val projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    val project = DiscoveredProject(
        identity = ProjectIdentity("smoke-project", projectRoot.toString(), projectRoot.toString(), null),
        name = projectRoot.fileName?.toString().orEmpty(),
        path = projectRoot.toString(),
        gitRoot = projectRoot.toString(),
        gitRemote = null,
        currentBranch = null,
        agents = emptyList(),
        lastActivity = null,
    )
    val providers = listOf<McpProvider>(
        AntigravityMcpProvider(),
        ClaudeMcpProvider(),
        ClineMcpProvider(),
        CodexMcpProvider(),
        CopilotMcpProvider(),
        CursorMcpProvider(),
        GrokMcpProvider(),
        KiroMcpProvider(),
        OpenCodeMcpProvider(),
        QwenMcpProvider(),
    )
    val records = mutableListOf<RawMcpServer>()

    println("AgentHub MCP Discovery")
    providers.forEach { provider ->
        val global = runCatching(provider::discoverGlobal)
        val projectServers = runCatching { provider.discoverProject(project) }
        global.getOrNull()?.let(records::addAll)
        projectServers.getOrNull()?.let(records::addAll)
        println(
            "${provider.agentId}: global=${global.getOrNull()?.size ?: "failed"}, " +
                "project=${projectServers.getOrNull()?.size ?: "failed"}",
        )
    }

    val normalized = McpDiscoveryService(providers = emptyList()).normalize(records)
    println("Normalized servers: ${normalized.size}")
    println("Configured environment variable names: ${normalized.sumOf { it.environmentVariableNames.size }}")
    println("No configuration values, commands, URLs, arguments, names, or paths were displayed.")
}
