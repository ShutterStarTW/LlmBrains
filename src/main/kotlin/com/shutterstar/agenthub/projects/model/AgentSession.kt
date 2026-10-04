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
    /** How many messages the user typed in this session; null when the transcript could not be read. */
    val messageCount: Int? = null,
    /** The user's first message (one line, truncated). The shortened first message is persisted as a session-name fallback. */
    val firstMessage: String? = null,
    val statistics: Map<String, String> = emptyMap(),
)
