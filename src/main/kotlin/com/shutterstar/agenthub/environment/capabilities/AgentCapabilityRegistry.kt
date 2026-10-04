package com.shutterstar.agenthub.environment.capabilities

object AgentCapabilityRegistry {
    private val capabilitiesByAgentId = mapOf(
        "antigravity" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = true,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
            supportsConfig = true,
        ),
        "claude" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = false,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
            supportsConfig = true,
        ),
        "cline" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = false,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
            supportsConfig = true,
        ),
        "codex" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = true,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
            supportsConfig = true,
        ),
        "copilot" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = true,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
            supportsConfig = true,
        ),
        "cursor" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = true,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
            supportsConfig = true,
        ),
        "grok" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = true,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
            supportsConfig = true,
        ),
        "kiro" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = false,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
            supportsConfig = true,
        ),
        "opencode" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = true,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
            supportsConfig = true,
        ),
        "qwen" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = false,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
            supportsConfig = true,
        ),
    )

    fun capabilitiesFor(agentId: String): AgentCapabilities =
        capabilitiesByAgentId[agentId] ?: AgentCapabilities.NONE

    fun agentIdsSupportingSharedSkills(): Set<String> =
        capabilitiesByAgentId
            .filterValues { it.supportsSharedAgentSkills }
            .keys

    fun agentIdsSupportingSkills(): Set<String> =
        capabilitiesByAgentId
            .filterValues { it.supportsSkills }
            .keys
}
