package com.shutterstar.agenthub.environment.capabilities

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentCapabilityRegistryTest {
    @Test
    fun `should expose only verified shared skill compatibility`() {
        assertEquals(AgentCapabilities.NONE, AgentCapabilityRegistry.capabilitiesFor("amp"))
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("antigravity").supportsMcp)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("antigravity").supportsSharedAgentSkills)
        assertTrue(AgentCapabilityRegistry.supportsAgentSkills("claude"))
        assertTrue(AgentCapabilityRegistry.supportsAgentSkills("codex"))
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("codex").supportsSharedAgentSkills)
        assertFalse(AgentCapabilityRegistry.capabilitiesFor("claude").supportsSharedAgentSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("cline").supportsInstructions)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("cursor").supportsInstructions)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("cursor").supportsSharedAgentSkills)
        assertTrue(AgentCapabilityRegistry.supportsAgentSkills("grok"))
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("grok").supportsSharedAgentSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("grok").supportsProjectMcp)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("opencode").supportsMcp)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("opencode").supportsInstructions)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("copilot").supportsProjectMcp)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("copilot").supportsSharedAgentSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("kiro").supportsMcp)
        assertTrue(AgentCapabilityRegistry.supportsAgentSkills("qwen"))
        assertFalse(AgentCapabilityRegistry.supportsAgentSkills("unknown-agent"))
    }
}
