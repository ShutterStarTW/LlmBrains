package com.shutterstar.agenthub.projects.model

import java.time.Instant

data class RawAgentProject(
    val agentId: String,
    val rawProjectPath: String?,
    val sessionId: String,
    val startedAt: Instant?,
    val updatedAt: Instant?,
    val sourcePath: String?,
    val metadata: Map<String, String> = emptyMap(),
)
