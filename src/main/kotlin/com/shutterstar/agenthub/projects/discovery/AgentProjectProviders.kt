package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.AgentRuntime

object AgentProjectProviders {
    /** One factory per agent, by agent id: the providers resolve their data directories when they are created. */
    private val factories: List<Pair<String, () -> AgentProjectProvider>> = listOf(
        "antigravity" to { AntigravityProjectProvider() },
        "claude" to { ClaudeProjectProvider() },
        "cline" to { ClineProjectProvider() },
        "codex" to { CodexProjectProvider() },
        "copilot" to { CopilotProjectProvider() },
        "cursor" to { CursorProjectProvider() },
        "freebuff" to { FreebuffProjectProvider() },
        "grok" to { GrokProjectProvider() },
        "junie" to { JunieProjectProvider() },
        "kilo" to { KiloProjectProvider() },
        "kimi" to { KimiProjectProvider() },
        "kiro" to { KiroProjectProvider() },
        "mimo" to { MimoProjectProvider() },
        "omp" to { OmpProjectProvider() },
        "opencode" to { OpenCodeProjectProvider() },
        "qwen" to { QwenProjectProvider() },
        "vibe" to { VibeProjectProvider() },
    )

    /** The agents that have a project provider; known without creating one (safe on the UI thread). */
    val agentIds: Set<String> = factories.mapTo(linkedSetOf()) { it.first }

    private val scoped = AgentRuntime.scoped { factories.map { (_, create) -> create() } }

    /** The providers for the current runtime - the host, or the selected WSL distro in WSL mode. */
    val all: List<AgentProjectProvider> get() = scoped.get()
}
