package com.shutterstar.agenthub.projects.model

import java.time.Instant

data class AgentProject(
    val agentId: String,
    val projectId: String,
    val sessionCount: Int,
    val lastActivity: Instant?,
    val sessions: List<AgentSession>,
)
