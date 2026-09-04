package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.model.DiscoveredProject

fun main(arguments: Array<String>) {
    if (arguments.any { it == "--help" || it == "-h" }) {
        println("Usage: project-discovery-smoke [--show-paths]")
        return
    }

    val showPaths = arguments.any { it == "--show-paths" }
    val availableProviders = AgentProjectProviders.all
        .filter { provider -> runCatching { provider.isAvailable() }.getOrDefault(false) }
        .map { it.agentId }
    val result = ProjectDiscoveryService().discover()
    val sessionCount = result.projects.sumOf { project -> project.agents.sumOf { it.sessionCount } }
    val sessionsByAgent = result.projects
        .flatMap { it.agents }
        .groupBy { it.agentId }
        .mapValues { (_, projects) -> projects.sumOf { it.sessionCount } }
        .toSortedMap()

    println("AgentHub Project Discovery")
    println("Available providers: ${availableProviders.joinToString().ifBlank { "none" }}")
    println("Projects: ${result.projects.size}")
    println("Sessions: $sessionCount")
    println("Sessions by agent: ${sessionsByAgent.entries.joinToString { "${it.key}=${it.value}" }}")
    println("Multi-agent projects: ${result.projects.count { it.agents.size > 1 }}")
    if (result.warnings.isNotEmpty()) {
        println("Warnings:")
        result.warnings.forEach { warning ->
            println("  ${safe(warning.agentId)}: ${safe(warning.message)}")
        }
    }

    println()
    result.projects.forEach { project -> printProject(project, showPaths) }
}

private fun printProject(project: DiscoveredProject, showPaths: Boolean) {
    val agentSummary = project.agents.joinToString { relation ->
        "${safe(relation.agentId)}=${relation.sessionCount}"
    }
    println("- ${safe(project.name)} | $agentSummary | last=${project.lastActivity ?: "unknown"}")
    if (showPaths) {
        println("  path=${safe(project.path ?: "unknown")}")
        project.gitRemote?.let { println("  remote=${safe(it)}") }
    }
}

private fun safe(value: String): String = value
    .map { character -> if (character.isISOControl()) '?' else character }
    .joinToString("")
    .take(MAX_DISPLAY_CHARACTERS)

private const val MAX_DISPLAY_CHARACTERS = 500
