package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompanionToolsTest {

    @Test
    fun `companion tool count is 4`() {
        assertEquals(4, CompanionTools.all.size)
    }

    @Test
    fun `all companion ids are non-blank, lowercase, and unique`() {
        CompanionTools.all.forEach {
            assertTrue(it.id.isNotBlank(), "blank id in: $it")
            assertEquals(it.id, it.id.lowercase().trim(), "id not lowercase/trimmed: ${it.id}")
            assertFalse(it.id.contains(' '), "id contains space: ${it.id}")
        }
        val ids = CompanionTools.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "duplicate companion id found")
    }

    @Test
    fun `companion ids do not collide with agent ids`() {
        val agentIds = CodingAgents.all.map { it.id }.toSet()
        CompanionTools.all.forEach {
            assertFalse(it.id in agentIds, "companion id collides with agent id: ${it.id}")
        }
    }

    @Test
    fun `all companions have non-blank commands and install hints`() {
        CompanionTools.all.forEach {
            assertTrue(it.command.isNotBlank(), "blank command for: ${it.id}")
            assertTrue(it.installHint.isNotBlank(), "blank installHint for: ${it.id}")
        }
    }

    @Test
    fun `all npm update hints reference a package name`() {
        CompanionTools.all
            .filter { "npm" in it.updateHint }
            .forEach {
                val parts = it.updateHint.trim().split("\\s+".toRegex())
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
