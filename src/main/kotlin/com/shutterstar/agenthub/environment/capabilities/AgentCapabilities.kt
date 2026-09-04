package com.shutterstar.agenthub.environment.capabilities

data class AgentCapabilities(
    val supportsSkills: Boolean,
    val supportsSharedAgentSkills: Boolean,
    val supportsMcp: Boolean,
    val supportsProjectMcp: Boolean,
    val supportsInstructions: Boolean,
) {
    companion object {
        val NONE = AgentCapabilities(
            supportsSkills = false,
            supportsSharedAgentSkills = false,
            supportsMcp = false,
            supportsProjectMcp = false,
            supportsInstructions = false,
        )
    }
}
