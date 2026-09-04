package com.shutterstar.agenthub.projects.model

import java.time.Instant

data class DiscoveredProject(
    val identity: ProjectIdentity,
    val name: String,
    val path: String?,
    val gitRoot: String?,
    val gitRemote: String?,
    val currentBranch: String?,
    val agents: List<AgentProject>,
    val lastActivity: Instant?,
)
