package com.shutterstar.agenthub.projects.ui

import com.shutterstar.agenthub.projects.model.AgentProject
import com.shutterstar.agenthub.projects.model.AgentSession
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class ProjectIndexUiModelTest {
    @Test
    fun `project search matches name path remote and agent name`() {
        val projects = listOf(
            project("AgentHub", "K:/IdeaProjects/LlmBrains", "github.com/team/agenthub", "codex", 2),
            project("Store", "K:/Work/store", null, "claude", 3),
        )
        val names = mapOf("codex" to "Codex CLI", "claude" to "Claude Code")

        assertEquals(listOf("AgentHub"), filter(projects, "llmbrains", names).map { it.name })
        assertEquals(listOf("AgentHub"), filter(projects, "team/agenthub", names).map { it.name })
        assertEquals(listOf("Store"), filter(projects, "Claude Code", names).map { it.name })
    }

    @Test
    fun `agent view aggregates projects and sessions`() {
        val projects = listOf(
            project("Recent", "K:/Recent", null, "codex", 2, "2026-08-31T09:00:00Z"),
            project("Older", "K:/Older", null, "codex", 3, "2026-08-30T09:00:00Z"),
            project("Claude", "K:/Claude", null, "claude", 4, "2026-08-29T09:00:00Z"),
        )

        val agents = ProjectIndexUiModel.agents(projects, "") { it.uppercase() }
        val codex = agents.first { it.agentId == "codex" }

        assertEquals("codex", codex.agentId)
        assertEquals(2, codex.projectCount)
        assertEquals(5, codex.sessionCount)
        assertEquals(listOf("Recent", "Older"), codex.projects.map { it.projectName })
        assertEquals(listOf("K:/Recent", "K:/Older"), codex.projects.map { it.projectPath })
        assertEquals(listOf("main", "main"), codex.projects.map { it.currentBranch })
    }

    @Test
    fun `agent view is sorted by name regardless of activity recency`() {
        val projects = listOf(
            project("Old", "K:/Old", null, "zed", 1, "2026-08-31T09:00:00Z"),
            project("Newer", "K:/Newer", null, "amp", 1, "2026-08-30T09:00:00Z"),
            project("Mid", "K:/Mid", null, "mid", 1, "2026-08-29T09:00:00Z"),
        )

        val agents = ProjectIndexUiModel.agents(projects, "") { it }

        assertEquals(listOf("amp", "mid", "zed"), agents.map { it.agentId })
    }

    @Test
    fun `agent search also matches participating project`() {
        val projects = listOf(project("AgentHub", "K:/AgentHub", "github.com/team/agenthub", "codex", 2))

        val pathMatch = ProjectIndexUiModel.agents(projects, "K:/AgentHub") { "Codex CLI" }
        val remoteMatch = ProjectIndexUiModel.agents(projects, "team/agenthub") { "Codex CLI" }

        assertEquals(listOf("codex"), pathMatch.map { it.agentId })
        assertEquals(listOf("codex"), remoteMatch.map { it.agentId })
    }

    @Test
    fun `agent project details label path and git and omit a missing branch row`() {
        val usage = AgentProjectUsage(
            projectId = "project",
            projectName = "AgentHub",
            projectPath = "K:/IdeaProjects/LlmBrains",
            gitRemote = "github.com/team/agenthub",
            currentBranch = " ",
            sessionCount = 2,
            lastActivity = null,
        )

        val lines = agentProjectUsageDetailLines(usage, "Unknown")

        assertEquals(
            listOf(
                "Path: K:/IdeaProjects/LlmBrains",
                "Git: github.com/team/agenthub",
                "Last activity: Unknown",
            ),
            lines,
        )
    }

    @Test
    fun `agent project details show one fallback row when path and git are missing`() {
        val usage = AgentProjectUsage(
            projectId = "project",
            projectName = "AgentHub",
            projectPath = null,
            gitRemote = null,
            currentBranch = null,
            sessionCount = 2,
            lastActivity = null,
        )

        assertEquals(
            listOf("Unknown location", "Last activity: Unknown"),
            agentProjectUsageDetailLines(usage, "Unknown"),
        )
    }

    private fun filter(
        projects: List<DiscoveredProject>,
        query: String,
        names: Map<String, String>,
    ) = ProjectIndexUiModel.filterProjects(projects, query) { names[it].orEmpty() }

    private fun project(
        name: String,
        path: String,
        remote: String?,
        agentId: String,
        sessionCount: Int,
        activity: String = "2026-08-31T08:00:00Z",
    ): DiscoveredProject {
        val lastActivity = Instant.parse(activity)
        val sessions = (1..sessionCount).map { index ->
            AgentSession("$agentId-$index", agentId, path, null, lastActivity, null)
        }
        val identity = ProjectIdentity("path:$path", path, null, remote)
        return DiscoveredProject(
            identity = identity,
            name = name,
            path = path,
            gitRoot = null,
            gitRemote = remote,
            currentBranch = "main",
            agents = listOf(AgentProject(agentId, identity.id, sessionCount, lastActivity, sessions)),
            lastActivity = lastActivity,
        )
    }
}
