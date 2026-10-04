package com.shutterstar.agenthub.projects.model

/**
 * Read-time view of the project index restricted to visible (installed) agents: drops the
 * relations of hidden agents, drops projects left without any agent, and recomputes each
 * project's `lastActivity` from the remaining relations so a removed agent's recent sessions
 * do not keep a project at the top of the list.
 */
object ProjectVisibility {
    fun filter(projects: List<DiscoveredProject>, isAgentVisible: (String) -> Boolean): List<DiscoveredProject> {
        var changed = false
        val visible = projects.mapNotNull { project ->
            val visibleAgents = project.agents.filter { isAgentVisible(it.agentId) }
            when {
                visibleAgents.size == project.agents.size -> project
                visibleAgents.isEmpty() -> {
                    changed = true
                    null
                }
                else -> {
                    changed = true
                    project.copy(
                        agents = visibleAgents,
                        lastActivity = visibleAgents.mapNotNull { it.lastActivity }.maxOrNull(),
                    )
                }
            }
        }
        return if (changed) visible.sortedWith(ProjectComparators.discoveredProjectByRecency) else visible
    }
}
