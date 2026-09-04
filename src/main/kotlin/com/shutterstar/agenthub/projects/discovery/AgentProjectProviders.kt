package com.shutterstar.agenthub.projects.discovery

object AgentProjectProviders {
    val all: List<AgentProjectProvider> = listOf(
        AntigravityProjectProvider(),
        ClaudeProjectProvider(),
        ClineProjectProvider(),
        CodexProjectProvider(),
        CopilotProjectProvider(),
        CursorProjectProvider(),
        GrokProjectProvider(),
        KiroProjectProvider(),
        OpenCodeProjectProvider(),
        QwenProjectProvider(),
    )
}
