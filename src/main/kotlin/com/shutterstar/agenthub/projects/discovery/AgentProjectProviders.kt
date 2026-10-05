package com.shutterstar.agenthub.projects.discovery

object AgentProjectProviders {
    val all: List<AgentProjectProvider> = listOf(
        AntigravityProjectProvider(),
        ClaudeProjectProvider(),
        ClineProjectProvider(),
        CodexProjectProvider(),
        CopilotProjectProvider(),
        CursorProjectProvider(),
        FreebuffProjectProvider(),
        GrokProjectProvider(),
        JunieProjectProvider(),
        KiloProjectProvider(),
        KimiProjectProvider(),
        KiroProjectProvider(),
        MimoProjectProvider(),
        OmpProjectProvider(),
        OpenCodeProjectProvider(),
        QwenProjectProvider(),
        VibeProjectProvider(),
    )
}
