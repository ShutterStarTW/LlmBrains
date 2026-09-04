package com.shutterstar.agenthub.environment.capabilities

object AgentCapabilityRegistry {
    private val capabilitiesByAgentId = mapOf(
        "antigravity" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = true,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
        ),
        "claude" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = false,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
        ),
        "cline" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = false,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
        ),
        "codex" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = true,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
        ),
        "copilot" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = true,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
        ),
        "cursor" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = true,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
        ),
        "grok" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = true,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
        ),
        "kiro" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = false,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
        ),
        "opencode" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = true,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
        ),
        "qwen" to AgentCapabilities(
            supportsSkills = true,
            supportsSharedAgentSkills = false,
            supportsMcp = true,
            supportsProjectMcp = true,
            supportsInstructions = true,
        ),
    )

    fun capabilitiesFor(agentId: String): AgentCapabilities =
        capabilitiesByAgentId[agentId] ?: AgentCapabilities.NONE

    fun supportsAgentSkills(agentId: String): Boolean =
        capabilitiesFor(agentId).supportsSkills

    fun agentIdsSupportingSharedSkills(): Set<String> =
        capabilitiesByAgentId
            .filterValues { it.supportsSharedAgentSkills }
            .keys
}
