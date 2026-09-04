package com.shutterstar.agenthub.environment.discovery

import com.shutterstar.agenthub.environment.model.AgentEnvironment
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.util.Locale

class AgentEnvironmentDiscoveryService(
    private val projectDiscovery: ProjectEnvironmentDiscoveryService,
) {
    fun discover(
        agentId: String,
        projects: List<DiscoveredProject>,
    ): AgentEnvironment = aggregate(
        agentId,
        projects
            .filter { project -> project.agents.any { it.agentId == agentId } }
            .map { project -> projectDiscovery.forAgent(projectDiscovery.discover(project), agentId) },
    )

    fun aggregate(
        agentId: String,
        environments: List<AgentEnvironment>,
    ): AgentEnvironment = AgentEnvironment(
        agentId = agentId,
        skills = environments
            .flatMap(AgentEnvironment::skills)
            .distinctBy { skill ->
                if (skill.scope == SkillScope.GLOBAL) {
                    "global:${skill.identity.id}"
                } else {
                    "project:${skill.identity.id}:${skill.sources.map { it.path }.sorted().joinToString("|")}"
                }
            }
            .sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.scope })),
        mcpServers = environments
            .flatMap(AgentEnvironment::mcpServers)
            .distinctBy { server ->
                if (server.scope == McpScope.GLOBAL) {
                    "global:${server.id}"
                } else {
                    "project:${server.id}:${server.sources.map { it.configPath }.sorted().joinToString("|")}"
                }
            }
            .sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.scope })),
        instructions = environments
            .flatMap(AgentEnvironment::instructions)
            .distinctBy { source -> listOf(source.path, source.scope, source.type) }
            .sortedWith(compareBy({ it.scope }, { it.path.lowercase(Locale.ROOT) })),
        warnings = environments
            .flatMap(AgentEnvironment::warnings)
            .distinct(),
    )
}
