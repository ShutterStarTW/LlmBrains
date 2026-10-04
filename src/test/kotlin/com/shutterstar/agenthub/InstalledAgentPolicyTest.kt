package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstalledAgentPolicyTest {
    @Test
    fun `a missing result map makes every agent unknown and invisible`() {
        assertEquals(InstallState.UNKNOWN, InstalledAgentPolicy.state("claude", null))
        assertFalse(InstalledAgentPolicy.isVisible("claude", null))
        assertTrue(InstalledAgentPolicy.visibleIds(listOf("claude", "codex"), null).isEmpty())
    }

    @Test
    fun `an agent id absent from a non-null map is unknown, not not-installed`() {
        val results = mapOf("claude" to true)

        assertEquals(InstallState.UNKNOWN, InstalledAgentPolicy.state("codex", results))
        assertFalse(InstalledAgentPolicy.isVisible("codex", results))
    }

    @Test
    fun `only installed agents are visible`() {
        val results = mapOf("claude" to true, "codex" to false, "cursor" to true)

        assertEquals(InstallState.INSTALLED, InstalledAgentPolicy.state("claude", results))
        assertEquals(InstallState.NOT_INSTALLED, InstalledAgentPolicy.state("codex", results))
        assertEquals(
            setOf("claude", "cursor"),
            InstalledAgentPolicy.visibleIds(listOf("claude", "codex", "cursor", "kiro"), results),
        )
    }
}
