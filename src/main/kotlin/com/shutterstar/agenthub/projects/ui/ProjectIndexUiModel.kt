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

    fun agents(
        projects: List<DiscoveredProject>,
        query: String,
        agentName: (String) -> String,
    ): List<DiscoveredAgentSummary> {
        val needle = query.trim()
        return projects.flatMap { project -> project.agents.map { project to it } }
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
            }
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
