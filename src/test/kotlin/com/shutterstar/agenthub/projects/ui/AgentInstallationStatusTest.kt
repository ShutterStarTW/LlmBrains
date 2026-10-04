package com.shutterstar.agenthub.projects.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentInstallationStatusTest {
    @Test
    fun `unknown detection never claims installation state`() {
        assertEquals("Installation not checked", agentInstallationStatus("codex", null, 0))
        assertEquals("Installation not checked", agentInstallationStatus("codex", mapOf("claude" to true), 1))
        assertEquals("Installation not checked", agentInstallationStatus("codex", mapOf("codex" to true), 0))
    }

    @Test
    fun `known detection names the result as an earlier check`() {
        val checkedAt = 1_700_000_000_000L
        assertTrue(agentInstallationStatus("codex", mapOf("codex" to true), checkedAt).startsWith("Installed at last check · "))
        assertTrue(agentInstallationStatus("codex", mapOf("codex" to false), checkedAt).startsWith("Not detected at last check · "))
    }
}