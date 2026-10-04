package com.shutterstar.agenthub.projects.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ProjectVisibilityTest {
    private val old = Instant.parse("2026-01-01T00:00:00Z")
    private val recent = Instant.parse("2026-09-01T00:00:00Z")

    @Test
    fun `hidden agents' relations are removed and projects left without agents disappear`() {
        val mixed = project("mixed", agent("claude", old), agent("codex", recent))
        val onlyHidden = project("only-hidden", agent("codex", recent))

        val visible = ProjectVisibility.filter(listOf(mixed, onlyHidden)) { it == "claude" }

        assertEquals(listOf("mixed"), visible.map { it.identity.id })
        assertEquals(listOf("claude"), visible.single().agents.map { it.agentId })
    }

    @Test
    fun `last activity is recomputed from the remaining relations`() {
        val mixed = project("mixed", agent("claude", old), agent("codex", recent))

        val visible = ProjectVisibility.filter(listOf(mixed)) { it == "claude" }.single()

        assertEquals(old, visible.lastActivity)
    }

    @Test
    fun `projects are re-sorted by the recomputed activity`() {
        val a = project("a", agent("claude", old), agent("codex", recent))
        val b = project("b", agent("claude", Instant.parse("2026-05-01T00:00:00Z")))

        val visible = ProjectVisibility.filter(listOf(a, b)) { it == "claude" }

        assertEquals(listOf("b", "a"), visible.map { it.identity.id })
    }

    @Test
    fun `an untouched list is returned as is, including projects that never had an agent relation`() {
        val bare = project("bare")
        val full = project("full", agent("claude", recent))

        val visible = ProjectVisibility.filter(listOf(full, bare)) { true }

        assertEquals(listOf("full", "bare"), visible.map { it.identity.id })
        assertSame(full, visible.first())
    }

    @Test
    fun `nothing is visible when no agent is`() {
        assertTrue(ProjectVisibility.filter(listOf(project("p", agent("claude", recent)))) { false }.isEmpty())
    }

    private fun agent(id: String, activity: Instant) = AgentProject(id, "p", 1, activity, emptyList())

    private fun project(id: String, vararg agents: AgentProject) = DiscoveredProject(
        identity = ProjectIdentity(id, "K:/Projects/$id", null, null),
        name = id,
        path = "K:/Projects/$id",
        gitRoot = null,
        gitRemote = null,
        currentBranch = null,
        agents = agents.toList(),
        lastActivity = agents.mapNotNull { it.lastActivity }.maxOrNull(),
    )
}
