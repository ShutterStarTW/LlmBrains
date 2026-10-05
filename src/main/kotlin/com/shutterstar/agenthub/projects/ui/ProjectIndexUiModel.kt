package com.shutterstar.agenthub.projects.ui

import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.time.Instant

data class DiscoveredAgentSummary(
    val agentId: String,
    val name: String,
    val projectCount: Int,
    val sessionCount: Int,
    val lastActivity: Instant?,
    val projects: List<AgentProjectUsage>,
)

data class AgentProjectUsage(
    val projectId: String,
    val projectName: String,
    val projectPath: String?,
    val gitRemote: String?,
    val currentBranch: String? = null,
    val sessionCount: Int,
    val lastActivity: Instant?,
)

object ProjectIndexUiModel {
    /** The empty-list text: still discovering, nothing matches the search, or genuinely nothing found. */
    fun emptyText(noun: String, refreshing: Boolean, filtered: Boolean): String = when {
        refreshing -> "Discovering $noun…"
        filtered -> "No $noun match this search"
        else -> "No $noun discovered"
    }

    /** "3 of 24 projects" while a search narrows the list, "24 projects" otherwise. */
    fun countLabel(shown: Int, total: Int, noun: String, filtered: Boolean): String =
        if (filtered) "$shown of $total $noun" else "$total $noun"

    fun filterProjects(
        projects: List<DiscoveredProject>,
        query: String,
        agentName: (String) -> String,
    ): List<DiscoveredProject> {
        val needle = query.trim()
        if (needle.isEmpty()) return projects

        return projects.filter { project ->
            project.name.contains(needle, ignoreCase = true) ||
                project.path.orEmpty().contains(needle, ignoreCase = true) ||
                project.gitRemote.orEmpty().contains(needle, ignoreCase = true) ||
                project.agents.any { relation ->
                    relation.agentId.contains(needle, ignoreCase = true) ||
                        agentName(relation.agentId).contains(needle, ignoreCase = true)
                }
        }
    }

    /**
     * The agents of the Agents tab: those with sessions in [projects], plus every id in [installedAgentIds]
     * that has none yet (shown with zero projects/sessions - its environment exists regardless).
     */
    fun agents(
        projects: List<DiscoveredProject>,
        query: String,
        installedAgentIds: Set<String> = emptySet(),
        agentName: (String) -> String,
    ): List<DiscoveredAgentSummary> {
        val needle = query.trim()
        val withSessions = projects.flatMapTo(mutableSetOf()) { project -> project.agents.map { it.agentId } }
        val withoutSessions = (installedAgentIds - withSessions).map { agentId ->
            DiscoveredAgentSummary(agentId, agentName(agentId), 0, 0, null, emptyList())
        }
        return (projects.flatMap { project -> project.agents.map { project to it } }
            .groupBy { (_, relation) -> relation.agentId }
            .map { (agentId, relations) ->
                val usages = relations.map { (project, relation) ->
                    AgentProjectUsage(
                        projectId = project.identity.id,
                        projectName = project.name,
                        projectPath = project.path,
                        gitRemote = project.gitRemote,
                        currentBranch = project.currentBranch,
                        sessionCount = relation.sessionCount,
                        lastActivity = relation.lastActivity,
                    )
                }.sortedWith(
                    compareByDescending<AgentProjectUsage> { it.lastActivity ?: Instant.MIN }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.projectName },
                )
                DiscoveredAgentSummary(
                    agentId = agentId,
                    name = agentName(agentId),
                    projectCount = usages.size,
                    sessionCount = usages.sumOf { it.sessionCount },
                    lastActivity = usages.mapNotNull { it.lastActivity }.maxOrNull(),
                    projects = usages,
                )
            } + withoutSessions)
            .filter { summary ->
                needle.isEmpty() ||
                    summary.agentId.contains(needle, ignoreCase = true) ||
                    summary.name.contains(needle, ignoreCase = true) ||
                    summary.projects.any { usage ->
                        usage.projectName.contains(needle, ignoreCase = true) ||
                            usage.projectPath.orEmpty().contains(needle, ignoreCase = true) ||
                            usage.gitRemote.orEmpty().contains(needle, ignoreCase = true)
                    }
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}
