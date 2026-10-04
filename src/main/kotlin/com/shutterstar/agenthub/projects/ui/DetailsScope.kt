package com.shutterstar.agenthub.projects.ui

import com.shutterstar.agenthub.projects.model.DiscoveredProject

/**
 * What a details view (Sessions, Environment) is filtered by. The Projects and Agents tabs show
 * the same data from two directions — one project across its agents, or one agent across its
 * projects — so the shared detail panels take the direction as a value instead of existing twice.
 */
internal sealed interface DetailsScope {
    data class ForProject(val project: DiscoveredProject) : DetailsScope

    data class ForAgent(
        val agentId: String,
        val projects: List<DiscoveredProject>,
    ) : DetailsScope

    /** Stable identity of the selected entity, used to decide whether a selection *changed*. */
    val key: String
        get() = when (this) {
            is ForProject -> "project:${project.identity.id}"
            is ForAgent -> "agent:$agentId"
        }
}
