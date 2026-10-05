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
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("claude").supportsSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("codex").supportsSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("codex").supportsSharedAgentSkills)
        assertFalse(AgentCapabilityRegistry.capabilitiesFor("claude").supportsSharedAgentSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("cline").supportsInstructions)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("cursor").supportsInstructions)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("cursor").supportsSharedAgentSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("grok").supportsSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("grok").supportsSharedAgentSkills)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("grok").supportsProjectMcp)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("opencode").supportsMcp)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("opencode").supportsInstructions)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("copilot").supportsProjectMcp)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("copilot").supportsSharedAgentSkills)
        val kilo = AgentCapabilityRegistry.capabilitiesFor("kilo")
        assertTrue(kilo.supportsSkills && kilo.supportsSharedAgentSkills && kilo.supportsMcp && kilo.supportsProjectMcp)
        assertTrue(kilo.supportsInstructions && kilo.supportsConfig)
        val omp = AgentCapabilityRegistry.capabilitiesFor("omp")
        assertTrue(omp.supportsSkills && omp.supportsSharedAgentSkills && omp.supportsMcp && omp.supportsProjectMcp)
        assertTrue(omp.supportsInstructions && omp.supportsConfig)
        val kimi = AgentCapabilityRegistry.capabilitiesFor("kimi")
        assertTrue(kimi.supportsSkills && kimi.supportsSharedAgentSkills && kimi.supportsMcp && kimi.supportsProjectMcp)
        assertTrue(kimi.supportsInstructions && kimi.supportsConfig)
        listOf("freebuff", "junie", "mimo", "vibe").forEach { agent ->
            val capabilities = AgentCapabilityRegistry.capabilitiesFor(agent)
            assertTrue(capabilities.supportsSkills && capabilities.supportsSharedAgentSkills, agent)
            assertTrue(capabilities.supportsMcp && capabilities.supportsProjectMcp, agent)
            assertTrue(capabilities.supportsInstructions && capabilities.supportsConfig, agent)
        }
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("kiro").supportsMcp)
        assertTrue(AgentCapabilityRegistry.capabilitiesFor("qwen").supportsSkills)
        assertFalse(AgentCapabilityRegistry.capabilitiesFor("unknown-agent").supportsSkills)
    }
}
