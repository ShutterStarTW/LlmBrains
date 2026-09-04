package com.shutterstar.agenthub.projects.model

import java.time.Instant

data class AgentSession(
    val id: String,
    val agentId: String,
    val projectPath: String?,
    val startedAt: Instant?,
    val updatedAt: Instant?,
    val sourcePath: String?,
    val title: String? = null,
    val nativeResumeId: String? = null,
)
