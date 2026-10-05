package com.shutterstar.agenthub.environment.persistence

import com.shutterstar.agenthub.environment.model.ProjectEnvironment
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.storage.MemoryStateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class EnvironmentIndexServiceTest {
    @Test
    fun `recorded environment is retrievable by project id`() {
        val service = EnvironmentIndexService()
        val environment = environment("project-a")

        service.record("project-a", environment)

        assertEquals(environment, service.cachedEnvironment("project-a"))
        assertNull(service.cachedEnvironment("project-b"))
    }

    @Test
    fun `cached environment survives a simulated IDE restart via state reload`() {
        val recorded = EnvironmentIndexService()
        recorded.record("project-a", environment("project-a"))
        val persistedState = recorded.state

        val reloaded = EnvironmentIndexService()
        reloaded.loadState(persistedState)

        assertEquals(environment("project-a"), reloaded.cachedEnvironment("project-a"))
    }

    @Test
    fun `re-recording a project replaces its previous snapshot without duplicating it`() {
        val service = EnvironmentIndexService()

        service.record("project-a", environment("project-a", skillCount = 1))
        service.record("project-a", environment("project-a", skillCount = 2))

        assertEquals(1, service.state.projects.size)
        assertEquals(2, service.cachedEnvironment("project-a")?.skills?.size)
    }

    @Test
    fun `persisted project count is capped and evicts the oldest entries`() {
        var tick = 0L
        val service = EnvironmentIndexService(now = { Instant.EPOCH.plusSeconds(tick++) })

        repeat(300) { index -> service.record("project-$index", environment("project-$index")) }

        assertTrue(service.state.projects.size <= 256)
        assertNull(service.cachedEnvironment("project-0"))
        assertEquals(environment("project-299"), service.cachedEnvironment("project-299"))
    }

    @Test
    fun `re-recording identical content does not rewrite the store`() {
        var tick = 0L
        val store = MemoryStateStore(EnvironmentIndexState())
        val service = EnvironmentIndexService(now = { Instant.EPOCH.plusSeconds(tick++) }, store = store)

        service.record("project-a", environment("project-a"))
        val stampAfterFirst = store.stamp()
        service.record("project-a", environment("project-a"))

        assertEquals(stampAfterFirst, store.stamp())
        service.record("project-a", environment("project-a", skillCount = 2))
        assertTrue(store.stamp() != stampAfterFirst)
    }

    @Test
    fun `identical content is rewritten once the stored timestamp is older than an hour`() {
        var moment = Instant.EPOCH
        val store = MemoryStateStore(EnvironmentIndexState())
        val service = EnvironmentIndexService(now = { moment }, store = store)

        service.record("project-a", environment("project-a"))
        val stampAfterFirst = store.stamp()
        moment = moment.plusSeconds(2 * 3600)
        service.record("project-a", environment("project-a"))

        assertTrue(store.stamp() != stampAfterFirst)
    }

    @Test
    fun `clear removes every persisted project`() {
        val service = EnvironmentIndexService()
        service.record("project-a", environment("project-a"))

        service.clear()

        assertTrue(service.state.projects.isEmpty())
    }

    private fun environment(projectId: String, skillCount: Int = 1): ProjectEnvironment = ProjectEnvironment(
        projectId = projectId,
        agentIds = setOf("claude"),
        skills = emptyList(),
        mcpServers = emptyList(),
        instructions = emptyList(),
    ).let { base ->
        if (skillCount <= 0) base else base.copy(skills = List(skillCount) { fakeSkill(it) })
    }

    private fun fakeSkill(index: Int) = AgentSkill(
        identity = SkillIdentity("skill-$index"),
        name = "skill-$index",
        description = null,
        scope = SkillScope.PROJECT,
        sources = emptyList(),
        compatibleAgents = emptySet(),
        consistency = SkillConsistency.SINGLE_SOURCE,
    )
}
