package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.capabilities.AgentCapabilityRegistry
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SkillDiscoveryServiceTest {
    private val service = SkillDiscoveryService(providers = emptyList())

    @Test
    fun `should merge identical copies from two agents`() {
        val skills = service.normalize(
            listOf(
                record(agentId = "claude", path = "/claude/review", fingerprint = "same"),
                record(agentId = "codex", path = "/codex/review", fingerprint = "same"),
            ),
        )

        val skill = skills.single()
        assertEquals(SkillConsistency.IDENTICAL, skill.consistency)
        assertEquals(setOf("claude", "codex"), skill.compatibleAgents)
        assertEquals(2, skill.sources.size)
    }

    @Test
    fun `should flag materially different copies with the same name`() {
        val skill = service.normalize(
            listOf(
                record(agentId = "claude", path = "/claude/review", fingerprint = "first"),
                record(agentId = "codex", path = "/codex/review", fingerprint = "second"),
            ),
        ).single()

        assertEquals(SkillConsistency.DIFFERENT, skill.consistency)
        assertEquals(2, skill.sources.size)
    }

    @Test
    fun `should use capability metadata for shared skill compatibility`() {
        val skill = service.normalize(
            listOf(
                record(
                    agentId = null,
                    path = "/shared/review",
                    fingerprint = "shared",
                    shared = true,
                ),
            ),
        ).single()

        assertEquals(AgentCapabilityRegistry.agentIdsSupportingSharedSkills(), skill.compatibleAgents)
        assertEquals(SkillConsistency.SINGLE_SOURCE, skill.consistency)
    }

    @Test
    fun `should keep global and project definitions separate`() {
        val skills = service.normalize(
            listOf(
                record(agentId = "claude", path = "/global/review", fingerprint = "same"),
                record(
                    agentId = "claude",
                    path = "/project/review",
                    fingerprint = "same",
                    scope = SkillScope.PROJECT,
                ),
            ),
        )

        assertEquals(2, skills.size)
        assertEquals(setOf(SkillScope.GLOBAL, SkillScope.PROJECT), skills.mapTo(mutableSetOf()) { it.scope })
        assertNotEquals(skills[0].identity, skills[1].identity)
    }

    @Test
    fun `should normalize casing and remove repeated source records`() {
        val source = record(agentId = "claude", path = "/claude/review", fingerprint = "same")
        val skill = service.normalize(
            listOf(
                source.copy(name = " Review "),
                source.copy(name = "review"),
            ),
        ).single()

        assertEquals(1, skill.sources.size)
        assertEquals(SkillConsistency.SINGLE_SOURCE, skill.consistency)
        assertTrue(skill.identity.id.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `should carry the display title and project name into normalized sources`() {
        val skill = service.normalize(
            listOf(
                record(agentId = "claude", path = "/project/review", fingerprint = "same")
                    .copy(displayTitle = "Review Skill", projectName = "LlmBrains"),
            ),
        ).single()

        val source = skill.sources.single()
        assertEquals("Review Skill", source.displayTitle)
        assertEquals("LlmBrains", source.projectName)
    }

    @Test
    fun `should preserve healthy provider results when another provider fails`() {
        val failingProvider = object : SkillProvider {
            override val agentId: String = "broken"

            override fun discoverGlobal(): List<SkillSourceRecord> = error("fixture failure")

            override fun discoverProject(project: DiscoveredProject) = emptyList<SkillSourceRecord>()
        }
        val healthyProvider = object : SkillProvider {
            override val agentId: String = "claude"

            override fun discoverGlobal(): List<SkillSourceRecord> =
                listOf(record(agentId = agentId, path = "/claude/review", fingerprint = "healthy"))

            override fun discoverProject(project: DiscoveredProject) = emptyList<SkillSourceRecord>()
        }

        val skills = SkillDiscoveryService(listOf(failingProvider, healthyProvider)).discoverGlobal()

        assertEquals(listOf("review"), skills.map { it.name })
    }

    @Test
    fun `structured warning surfaces the failing provider without a raw exception message`() {
        val failingProvider = object : SkillProvider {
            override val agentId: String = "broken"

            override fun discoverGlobal(): List<SkillSourceRecord> = error("fixture failure with secrets")

            override fun discoverProject(project: DiscoveredProject) = emptyList<SkillSourceRecord>()
        }

        val (records, warnings) = SkillDiscoveryService(listOf(failingProvider)).discoverGlobalRecordsWithWarnings()

        assertTrue(records.isEmpty())
        val warning = warnings.single()
        assertEquals("broken", warning.agentId)
        assertEquals("global", warning.scope)
        assertTrue(warning.message.contains("IllegalStateException"))
        assertFalse(warning.message.contains("fixture failure with secrets"))
    }

    @Test
    fun `interrupted discovery stops before invoking later providers`() {
        val laterInvocations = AtomicInteger()
        val interruptedProvider = object : SkillProvider {
            override val agentId: String = "interrupted"

            override fun discoverGlobal(): List<SkillSourceRecord> = throw InterruptedException("stop")

            override fun discoverProject(project: DiscoveredProject) = emptyList<SkillSourceRecord>()
        }
        val laterProvider = object : SkillProvider {
            override val agentId: String = "later"

            override fun discoverGlobal(): List<SkillSourceRecord> {
                laterInvocations.incrementAndGet()
                return emptyList()
            }

            override fun discoverProject(project: DiscoveredProject) = emptyList<SkillSourceRecord>()
        }

        try {
            val (_, warnings) = SkillDiscoveryService(
                listOf(interruptedProvider, laterProvider),
            ).discoverGlobalRecordsWithWarnings()

            assertEquals(0, laterInvocations.get())
            assertEquals("InterruptedException", warnings.single().message.substringAfterLast(' '))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    private fun record(
        agentId: String?,
        path: String,
        fingerprint: String,
        shared: Boolean = false,
        scope: SkillScope = SkillScope.GLOBAL,
    ) = SkillSourceRecord(
        name = "review",
        path = path,
        description = "Reviews changes",
        agentId = agentId,
        scope = scope,
        shared = shared,
        fingerprint = fingerprint,
    )
}
