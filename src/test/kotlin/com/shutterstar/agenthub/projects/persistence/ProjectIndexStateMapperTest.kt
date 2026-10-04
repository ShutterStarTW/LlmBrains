package com.shutterstar.agenthub.projects.persistence

import com.intellij.util.xmlb.XmlSerializer
import com.shutterstar.agenthub.projects.model.AgentProject
import com.shutterstar.agenthub.projects.model.AgentSession
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ProjectIndexStateMapperTest {
    @Test
    fun `round trip preserves normalized project metadata`() {
        val project = project()
        val refreshedAt = Instant.parse("2026-08-31T08:00:00Z")

        val state = ProjectIndexStateMapper.encode(listOf(project), refreshedAt)
        val restored = ProjectIndexStateMapper.decode(state).single()

        assertEquals(refreshedAt.toEpochMilli(), state.refreshedAtEpochMillis)
        assertEquals(project.identity, restored.identity)
        assertEquals(project.name, restored.name)
        assertEquals(project.currentBranch, restored.currentBranch)
        assertEquals(project.agents.single().sessionCount, restored.agents.single().sessionCount)
        assertEquals(project.lastActivity, restored.lastActivity)
        assertEquals(project.agents.single().sessions.single().sourcePath, restored.agents.single().sessions.single().sourcePath)
        assertEquals(project.agents.single().sessions.single().nativeResumeId, restored.agents.single().sessions.single().nativeResumeId)
        assertEquals(42, restored.agents.single().sessions.single().messageCount)
        assertEquals(project.agents.single().sessions.single().statistics, restored.agents.single().sessions.single().statistics)
    }

    @Test
    fun `session names survive persistence`() {
        val restored = ProjectIndexStateMapper.decode(
            ProjectIndexStateMapper.encode(listOf(project()), Instant.EPOCH),
        ).single()

        assertEquals("private conversation title", restored.agents.single().sessions.single().title)
        assertEquals("private first prompt", restored.agents.single().sessions.single().firstMessage)
    }

    @Test
    fun `previous schema still restores sessions without names`() {
        val old = ProjectIndexStateMapper.encode(listOf(project()), Instant.EPOCH).copy(schemaVersion = 1)
        old.projects.single().agents.single().sessions.single().apply {
            title = null
            firstMessage = null
        }
        val restored = ProjectIndexStateMapper.decode(old).single().agents.single().sessions.single()
        assertEquals("session-1", restored.id)
        assertNull(restored.firstMessage)
    }

    @Test
    fun `state is compatible with IntelliJ XML persistence`() {
        val state = ProjectIndexStateMapper.encode(
            listOf(project()),
            Instant.parse("2026-08-31T08:00:00Z"),
        )

        val xml = XmlSerializer.serialize(state)
        val restored = XmlSerializer.deserialize(xml, ProjectIndexState::class.java)

        assertEquals(state, restored)
    }

    @Test
    fun `unknown schema and malformed identities are ignored`() {
        val unknownSchema = ProjectIndexState(schemaVersion = 999)
        val malformed = ProjectIndexState(
            projects = mutableListOf(ProjectIndexProjectState(identityId = "")),
        )

        assertTrue(ProjectIndexStateMapper.decode(unknownSchema).isEmpty())
        assertTrue(ProjectIndexStateMapper.decode(malformed).isEmpty())
    }

    private fun project(): DiscoveredProject {
        val updatedAt = Instant.parse("2026-08-31T07:59:00Z")
        val session = AgentSession(
            id = "session-1",
            agentId = "codex",
            projectPath = "K:/IdeaProjects/LlmBrains",
            startedAt = updatedAt.minusSeconds(120),
            updatedAt = updatedAt,
            sourcePath = "C:/Users/example/.codex/sessions/session-1.jsonl",
            title = "private conversation title",
            nativeResumeId = "session-1",
            messageCount = 42,
            firstMessage = "private first prompt",
            statistics = mapOf("models" to "gpt-test", "totalTokens" to "12345", "activeMillis" to "60000"),
        )
        val agent = AgentProject(
            agentId = "codex",
            projectId = "git:github.com/shutterstartw/llmbrains",
            sessionCount = 1,
            lastActivity = updatedAt,
            sessions = listOf(session),
        )
        return DiscoveredProject(
            identity = ProjectIdentity(
                id = "git:github.com/shutterstartw/llmbrains",
                canonicalPath = "K:/IdeaProjects/LlmBrains",
                gitRoot = "K:/IdeaProjects/LlmBrains",
                gitRemote = "github.com/shutterstartw/llmbrains",
            ),
            name = "LlmBrains",
            path = "K:/IdeaProjects/LlmBrains",
            gitRoot = "K:/IdeaProjects/LlmBrains",
            gitRemote = "github.com/shutterstartw/llmbrains",
            currentBranch = "main",
            agents = listOf(agent),
            lastActivity = updatedAt,
        )
    }
}
