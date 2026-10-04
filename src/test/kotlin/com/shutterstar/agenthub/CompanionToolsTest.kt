package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompanionToolsTest {

    @Test
    fun `companion ids are unique lowercase and do not collide with agent ids`() {
        val agentIds = CodingAgents.all.map { it.id }.toSet()
        CompanionTools.all.forEach {
            assertEquals(it.id, it.id.lowercase().trim(), "id not lowercase/trimmed: ${it.id}")
            assertTrue(it.id.isNotBlank() && !it.id.contains(' '), "invalid id: '${it.id}'")
            assertFalse(it.id in agentIds, "companion id collides with agent id: ${it.id}")
        }
        val ids = CompanionTools.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "duplicate companion id found")
    }

    @Test
    fun `every companion has a command install hint and website`() {
        CompanionTools.all.forEach {
            assertTrue(it.command.isNotBlank(), "blank command for: ${it.id}")
            assertTrue(it.installHint.isNotBlank(), "blank installHint for: ${it.id}")
            assertTrue(it.url.isNotBlank(), "blank URL for: ${it.id}")
        }
    }

    @Test
    fun `npm update hints reference a package name`() {
        CompanionTools.all
            .filter { "npm" in it.updateHint }
            .forEach {
                val parts = it.updateHint.trim().split(Regex("\\s+"))
                assertTrue(parts.size >= 2, "npm updateHint too short for: ${it.id}")
                assertTrue(parts.last().isNotBlank(), "blank package name in npm updateHint for: ${it.id}")
            }
    }

    @Test
    fun `isCompanion recognizes registered ids only`() {
        assertTrue(CompanionTools.isCompanion("repomix"))
        assertFalse(CompanionTools.isCompanion("claude"))
    }
}
