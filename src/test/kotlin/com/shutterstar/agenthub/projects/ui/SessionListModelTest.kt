package com.shutterstar.agenthub.projects.ui

import com.shutterstar.agenthub.projects.model.AgentProject
import com.shutterstar.agenthub.projects.model.AgentSession
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class SessionListModelTest {
    private val t1 = Instant.parse("2026-09-18T10:00:00Z")
    private val t2 = Instant.parse("2026-09-18T12:00:00Z")
    private val t3 = Instant.parse("2026-09-17T09:00:00Z")
    private val format: (Instant) -> String = { "T${it.epochSecond}" }
    private val utc = ZoneOffset.UTC

    private fun session(id: String, agentId: String, at: Instant, title: String? = null) =
        AgentSession(id, agentId, "/p", at, at, "/store/$id.jsonl", title)

    private fun project(id: String, name: String, vararg agents: AgentProject) = DiscoveredProject(
        ProjectIdentity(id, "/p/$id", null, null),
        name,
        "/p/$id",
        null,
        null,
        null,
        agents.toList(),
        agents.mapNotNull { it.lastActivity }.maxOrNull(),
    )

    private fun relation(agentId: String, projectId: String, vararg sessions: AgentSession) =
        AgentProject(agentId, projectId, sessions.size, sessions.mapNotNull { it.updatedAt }.maxOrNull(), sessions.toList())

    @Test
    fun `byAgent orders groups by recency and only expands the requested groups`() {
        val project = project(
            "a",
            "Alpha",
            relation("codex", "a", session("c1", "codex", t3)),
            relation("claude", "a", session("k1", "claude", t1), session("k2", "claude", t2, "Refactor auth")),
        )

        val collapsed = SessionListModel.byAgent(project, emptySet(), { "Name:$it" }, format)
        assertEquals(listOf("agent:claude", "agent:codex"), collapsed.map { it.groupKey })
        assertTrue(collapsed.all { it is SessionRowItem.Group && !it.expanded })

        val expanded = SessionListModel.byAgent(project, setOf("agent:claude"), { "Name:$it" }, format, utc)
        val rows = expanded.map { it::class.simpleName to it.groupKey }
        assertEquals(
            listOf("Group" to "agent:claude", "Session" to "agent:claude", "Session" to "agent:claude", "Group" to "agent:codex"),
            rows,
        )
        val header = expanded.first() as SessionRowItem.Group
        assertEquals("Name:claude", header.title)
        assertEquals("claude", header.agentId)
        assertTrue(header.expanded)
        assertEquals("2 sessions · Last activity: T${t2.epochSecond}", header.detail)
        val sessions = expanded.filterIsInstance<SessionRowItem.Session>()
        // Most recent first; a real title is shown, an untitled session falls back to its time.
        assertEquals(listOf("Refactor auth", "2026. 09. 18. 10:00"), sessions.map { it.title })
        assertEquals("2026. 09. 18. 12:00", sessions.first().timeLabel)
    }

    @Test
    fun `byProject groups one agent's sessions by project and never carries an agent id`() {
        val alpha = project("a", "Alpha", relation("claude", "a", session("k1", "claude", t3)), relation("codex", "a", session("c1", "codex", t2)))
        val beta = project("b", "Beta", relation("claude", "b", session("k9", "claude", t1)))
        val gamma = project("g", "Gamma", relation("codex", "g", session("c7", "codex", t2)))

        val rows = SessionListModel.byProject("claude", listOf(alpha, beta, gamma), setOf("project:b"), format)

        val groups = rows.filterIsInstance<SessionRowItem.Group>()
        assertEquals(listOf("Beta", "Alpha"), groups.map { it.title }, "Gamma has no Claude sessions and Beta is more recent")
        assertTrue(groups.all { it.agentId == null })
        val sessions = rows.filterIsInstance<SessionRowItem.Session>()
        assertEquals(listOf("k9"), sessions.map { it.session.id })
        assertEquals("project:b", sessions.single().groupKey)
    }

    @Test
    fun `an empty group never reports itself expanded and a single session is labelled singular`() {
        val project = project("a", "Alpha", AgentProject("kiro", "a", 0, null, emptyList()), relation("claude", "a", session("k1", "claude", t1)))

        val rows = SessionListModel.byAgent(project, setOf("agent:kiro", "agent:claude"), { it }, format)

        val kiro = rows.filterIsInstance<SessionRowItem.Group>().single { it.agentId == "kiro" }
        assertTrue(!kiro.expanded)
        assertTrue(kiro.detail.startsWith("0 sessions · Last activity: Unknown"))
        val claude = rows.filterIsInstance<SessionRowItem.Group>().single { it.agentId == "claude" }
        assertTrue(claude.detail.startsWith("1 session ·"))
    }

    @Test
    fun `defaultExpandedKeys opens only the most recent group`() {
        val project = project(
            "a",
            "Alpha",
            relation("codex", "a", session("c1", "codex", t1)),
            relation("claude", "a", session("k1", "claude", t2)),
        )
        val rows = SessionListModel.byAgent(project, emptySet(), { it }, format)

        assertEquals(setOf("agent:claude"), SessionListModel.defaultExpandedKeys(rows))
        assertEquals(emptySet<String>(), SessionListModel.defaultExpandedKeys(emptyList()))
    }

    @Test
    fun `a session is resumable only with a native id on a supported agent`() {
        val project = project(
            "a",
            "Alpha",
            relation(
                "claude",
                "a",
                session("k1", "claude", t1).copy(nativeResumeId = "k1"),
                session("k2", "claude", t2),
            ),
            relation("cursor", "a", session("u1", "cursor", t3).copy(nativeResumeId = "u1")),
        )

        val rows = SessionListModel.byAgent(project, setOf("agent:claude", "agent:cursor"), { it }, format)
        val byId = rows.filterIsInstance<SessionRowItem.Session>().associateBy { it.session.id }

        assertTrue(byId.getValue("k1").resumable)
        assertTrue(!byId.getValue("k2").resumable, "no native id")
        assertTrue(!byId.getValue("u1").resumable, "Cursor has no known resume command")
        assertEquals("This session has no resume ID recorded", byId.getValue("k2").resumeBlockedReason)
        assertEquals("AgentHub does not know a native resume command for this agent yet", byId.getValue("u1").resumeBlockedReason)
        assertNull(byId.getValue("k1").resumeBlockedReason)
    }

    @Test
    fun `blank titles fall back to the time label`() {
        val blank = session("s", "claude", t1, "   ")
        assertEquals("12:00", SessionListModel.sessionTitle(blank, "12:00"))
        assertEquals("Real", SessionListModel.sessionTitle(blank.copy(title = " Real "), "12:00"))
    }

    @Test
    fun `agent session title takes priority and first prompt is the fallback`() {
        val titled = session("s", "cursor", t1, "Generated title")

        assertEquals("Generated title", SessionListModel.sessionTitle(titled.copy(firstMessage = "fix the build"), "12:00"))
        assertEquals(
            "fix the build and tests",
            SessionListModel.sessionTitle(titled.copy(title = " ", firstMessage = "fix the build\n  and tests"), "12:00"),
        )
        assertEquals("Generated title", SessionListModel.sessionTitle(titled.copy(firstMessage = "  "), "12:00"))
        val untitled = session("e", "copilot", t1)
        assertEquals("Empty session", SessionListModel.sessionTitle(untitled.copy(messageCount = 0), "12:00"))
        assertEquals("12:00", SessionListModel.sessionTitle(untitled, "12:00"), "unknown count: no claim it is empty")
        assertEquals("1 prompt", SessionListModel.byAgent(
            project("a", "Alpha", relation("cursor", "a", session("s1", "cursor", t1).copy(messageCount = 1))),
            setOf("agent:cursor"),
            { it },
            format,
            utc,
        ).filterIsInstance<SessionRowItem.Session>().single().detail)
    }

    @Test
    fun `dateRangeLabel shortens the end side by what it shares with the start`() {
        fun at(value: String) = Instant.parse(value)
        fun range(from: String?, to: String?) = SessionListModel.dateRangeLabel(from?.let(::at), to?.let(::at), utc)

        assertEquals("2026. 04. 20. 08:05 - 09:05", range("2026-04-20T08:05:00Z", "2026-04-20T09:05:00Z"))
        assertEquals("2026. 04. 20. 10:34 - 04. 21. 01:55", range("2026-04-20T10:34:00Z", "2026-04-21T01:55:00Z"))
        assertEquals("2025. 12. 31. 23:50 - 2026. 01. 01. 00:10", range("2025-12-31T23:50:00Z", "2026-01-01T00:10:00Z"))
        assertEquals("2026. 04. 20. 08:05", range("2026-04-20T08:05:10Z", "2026-04-20T08:05:50Z"), "same minute")
        assertEquals("2026. 04. 20. 08:05", range(null, "2026-04-20T08:05:00Z"))
        assertEquals("2026. 04. 20. 08:05", range("2026-04-20T08:05:00Z", null))
        assertEquals("Unknown time", range(null, null))
    }

    @Test
    fun `session detail combines message count with the range only when a real title exists`() {
        val project = project(
            "a",
            "Alpha",
            relation(
                "claude",
                "a",
                session("titled", "claude", t1, "Refactor auth").copy(updatedAt = t2, messageCount = 12),
                session("untitled", "claude", t3).copy(messageCount = 3),
                session("bare", "claude", t3),
            ),
        )

        val rows = SessionListModel.byAgent(project, setOf("agent:claude"), { it }, format, utc)
        val byId = rows.filterIsInstance<SessionRowItem.Session>().associateBy { it.session.id }

        assertEquals("12 prompts · 2026. 09. 18. 10:00 - 12:00", byId.getValue("titled").detail)
        assertEquals("3 prompts", byId.getValue("untitled").detail, "no real title: just the count, not a repeated date")
        assertEquals(null, byId.getValue("bare").detail, "neither a title nor a count: nothing left to add")
    }
}
