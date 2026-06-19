package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodingAgentsTest {

    @Test
    fun `all agents have non-blank ids`() {
        CodingAgents.all.forEach { assertTrue(it.id.isNotBlank(), "blank id in: $it") }
    }

    @Test
    fun `all agent ids are unique`() {
        val ids = CodingAgents.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "duplicate agent id found")
    }

    @Test
    fun `all agents have non-blank commands`() {
        CodingAgents.all.forEach { assertTrue(it.command.isNotBlank(), "blank command for: ${it.id}") }
    }

    @Test
    fun `all agents have non-blank install hints`() {
        CodingAgents.all.forEach { assertTrue(it.installHint.isNotBlank(), "blank installHint for: ${it.id}") }
    }

    @Test
    fun `all agent ids are lowercase with no whitespace`() {
        CodingAgents.all.forEach {
            assertEquals(it.id, it.id.lowercase().trim(), "id not lowercase/trimmed: ${it.id}")
            assertFalse(it.id.contains(' '), "id contains space: ${it.id}")
        }
    }

    @Test
    fun `all npm update hints reference a package name`() {
        CodingAgents.all
            .filter { "npm" in it.updateHint }
            .forEach {
                val parts = it.updateHint.trim().split("\\s+".toRegex())
                assertTrue(parts.size >= 2, "npm updateHint too short for: ${it.id}")
                assertTrue(parts.last().isNotBlank(), "blank package name in npm updateHint for: ${it.id}")
            }
    }

    @Test
    fun `agent count is 30`() {
        assertEquals(30, CodingAgents.all.size)
    }
}