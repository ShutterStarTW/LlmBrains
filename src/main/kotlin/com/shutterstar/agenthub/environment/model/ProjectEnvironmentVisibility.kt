package com.shutterstar.agenthub.environment.model

/** Restricts a persisted environment snapshot to the agents currently present in the project. */
fun ProjectEnvironment.visibleTo(allowedAgentIds: Set<String>): ProjectEnvironment = copy(
    agentIds = agentIds.intersect(allowedAgentIds),
    skills = skills.mapNotNull { skill ->
        val visible = skill.compatibleAgents.intersect(allowedAgentIds)
        if (visible.isEmpty()) null else skill.copy(
            compatibleAgents = visible,
            sources = skill.sources.filter { it.agentId == null || it.agentId in allowedAgentIds },
        )
    },
    mcpServers = mcpServers.mapNotNull { server ->
        val sources = server.sources.filter { it.agentId in allowedAgentIds }
        if (sources.isEmpty()) null else server.copy(sources = sources)
    },
    instructions = instructions.mapNotNull { source ->
        val visible = source.agentIds.intersect(allowedAgentIds)
        if (visible.isEmpty()) null else source.copy(agentIds = visible)
    },
    configs = configs.filter { it.agentId in allowedAgentIds },
    warnings = warnings.filter { it.agentId == null || it.agentId in allowedAgentIds },
)
