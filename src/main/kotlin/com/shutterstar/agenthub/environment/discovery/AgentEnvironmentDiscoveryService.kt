package com.shutterstar.agenthub.environment.discovery

import com.shutterstar.agenthub.environment.model.AgentEnvironment
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.util.Locale

class AgentEnvironmentDiscoveryService(
    private val projectDiscovery: ProjectEnvironmentDiscoveryService,
    private val cachedEnvironments: () -> Map<String, com.shutterstar.agenthub.environment.model.ProjectEnvironment> = {
        com.shutterstar.agenthub.environment.persistence.EnvironmentIndexService.getInstance().cachedEnvironments()
    },
    private val isAgentVisible: (String) -> Boolean = { true },
) {
    /** Last known related-project data, with global configs when already discovered; never scans. */
    fun cached(agentId: String, projects: List<DiscoveredProject>): AgentEnvironment? {
        if (!isAgentVisible(agentId)) return aggregate(agentId, emptyList())
        val indexed = cachedEnvironments()
        val environments = projects.filter { project -> project.agents.any { it.agentId == agentId } }
            .mapNotNull { project -> indexed[project.identity.id]?.let { projectDiscovery.forAgent(it, agentId) } }
        val global = projectDiscovery.cachedGlobalConfigsForAgent(agentId)
        if (environments.isEmpty() && global == null) return null
        return aggregate(agentId, listOfNotNull(global) + environments)
    }

    fun discover(
        agentId: String,
        projects: List<DiscoveredProject>,
    ): AgentEnvironment = if (!isAgentVisible(agentId)) {
        // A direct call for a hidden agent must not leak its data either.
        aggregate(agentId, emptyList())
    } else {
        aggregate(
            agentId,
            listOf(projectDiscovery.globalConfigsForAgent(agentId)) + projects
                .filter { project -> project.agents.any { it.agentId == agentId } }
                .map { project -> projectDiscovery.forAgent(projectDiscovery.discover(project), agentId) },
        )
    }

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
        configs = environments.flatMap(AgentEnvironment::configs)
            .distinctBy { listOf(it.agentId, it.path, it.scope.name) }
            .sortedWith(compareBy({ it.scope }, { it.path })),
        warnings = environments
            .flatMap(AgentEnvironment::warnings)
            .distinct(),
    )
}
