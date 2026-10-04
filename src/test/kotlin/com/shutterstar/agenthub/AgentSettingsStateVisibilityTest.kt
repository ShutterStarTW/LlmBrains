package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentSettingsStateVisibilityTest {
    @Test
    fun `nothing is visible and installation is unknown before the first detection`() {
        val settings = AgentSettingsState()

        assertFalse(settings.isInstallationKnown())
        assertEquals(InstallState.UNKNOWN, InstalledAgentPolicy.state("claude", settings.getDetectionResults()))
        assertTrue(settings.visibleAgentIds().isEmpty())
    }

    @Test
    fun `partial results leave the missing agents unknown and hidden`() {
        val settings = AgentSettingsState()

        settings.saveDetectionResults(mapOf("claude" to true, "codex" to false))

        assertTrue(settings.isInstallationKnown())
        assertEquals(setOf("claude"), settings.visibleAgentIds())
        assertEquals(InstallState.NOT_INSTALLED, InstalledAgentPolicy.state("codex", settings.getDetectionResults()))
        assertEquals(InstallState.UNKNOWN, InstalledAgentPolicy.state("cursor", settings.getDetectionResults()))
        assertFalse(settings.isAgentVisible("cursor"))
    }

    @Test
    fun `companion tools never count as visible agents`() {
        val settings = AgentSettingsState()
        val companion = CompanionTools.all.first().id

        settings.saveDetectionResults(mapOf("claude" to true, companion to true))

        assertEquals(setOf("claude"), settings.visibleAgentIds())
    }

    @Test
    fun `an environment reset returns to unknown`() {
        val settings = AgentSettingsState()
        settings.saveDetectionResults(mapOf("claude" to true))

        settings.clearDetectionResults()

        assertFalse(settings.isInstallationKnown())
        assertTrue(settings.visibleAgentIds().isEmpty())
    }

    @Test
    fun `every detection change bumps the generation and notifies listeners`() {
        val settings = AgentSettingsState()
        var notifications = 0
        val subscription = settings.addDetectionListener { notifications++ }
        val start = settings.detectionGeneration

        settings.saveDetectionResults(mapOf("claude" to false))
        settings.updateDetectionResult("claude", true)
        settings.clearDetectionResults()
        settings.markDetectionFailed()

        assertEquals(4, notifications)
        assertEquals(start + 4, settings.detectionGeneration)
        subscription.close()
        settings.updateDetectionResult("claude", true)
        assertEquals(4, notifications, "a closed subscription is not notified")
    }

    @Test
    fun `a failed detection is remembered until a result arrives and never counts as not-installed`() {
        val settings = AgentSettingsState()
        settings.markDetectionFailed()

        assertTrue(settings.detectionFailed)
        assertFalse(settings.isInstallationKnown())
        assertEquals(InstallState.UNKNOWN, InstalledAgentPolicy.state("claude", settings.getDetectionResults()))

        settings.saveDetectionResults(mapOf("claude" to true))

        assertFalse(settings.detectionFailed)
    }

    @Test
    fun `a throwing listener does not stop the others or the state change`() {
        val settings = AgentSettingsState()
        var reached = false
        settings.addDetectionListener { error("broken listener") }
        settings.addDetectionListener { reached = true }

        settings.saveDetectionResults(mapOf("claude" to true))

        assertTrue(reached)
        assertTrue(settings.isAgentVisible("claude"))
    }
}
