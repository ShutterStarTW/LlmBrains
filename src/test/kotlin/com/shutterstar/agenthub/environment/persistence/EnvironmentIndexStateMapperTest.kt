package com.shutterstar.agenthub.environment.persistence

import com.intellij.util.xmlb.XmlSerializer
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.environment.mcp.model.McpConsistency
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpServer
import com.shutterstar.agenthub.environment.mcp.model.McpSource
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.environment.model.EnvironmentWarning
import com.shutterstar.agenthub.environment.model.ProjectEnvironment
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class EnvironmentIndexStateMapperTest {
    @Test
    fun `round trip preserves display data and warnings without MCP connection values`() {
        val environment = environment()
        val refreshedAt = Instant.parse("2026-09-02T08:00:00Z")

        val state = EnvironmentIndexStateMapper.encode(mapOf("project" to environment), refreshedAt)
        val restored = EnvironmentIndexStateMapper.decode(state).getValue("project")

        val expected = environment.copy(
            mcpServers = environment.mcpServers.map { server ->
                server.copy(command = null, args = emptyList(), url = null)
            },
        )
        assertEquals(expected, restored)
    }

    @Test
    fun `state is compatible with IntelliJ XML persistence`() {
        val state = EnvironmentIndexStateMapper.encode(
            mapOf("project" to environment()),
            Instant.parse("2026-09-02T08:00:00Z"),
        )

        val xml = XmlSerializer.serialize(state)
        val restored = XmlSerializer.deserialize(xml, EnvironmentIndexState::class.java)

        assertEquals(state, restored)
    }

    @Test
    fun `unknown schema and blank identities are ignored`() {
        val unknownSchema = EnvironmentIndexState(schemaVersion = 999)
        val malformed = EnvironmentIndexState(
            projects = mutableListOf(EnvironmentIndexProjectState(projectId = "")),
        )

        assertTrue(EnvironmentIndexStateMapper.decode(unknownSchema).isEmpty())
        assertTrue(EnvironmentIndexStateMapper.decode(malformed).isEmpty())
    }

    private fun environment(): ProjectEnvironment = ProjectEnvironment(
        projectId = "project",
        agentIds = setOf("claude", "codex"),
        skills = listOf(
            AgentSkill(
                identity = SkillIdentity("skill-1"),
                name = "review",
                description = "Reviews code",
                scope = SkillScope.PROJECT,
                sources = listOf(
                    SkillSource(
                        "claude",
                        "/project/.claude/skills/review",
                        SkillScope.PROJECT,
                        false,
                        "fp-claude",
                        displayTitle = "Review Skill",
                        projectName = "LlmBrains",
                    ),
                    SkillSource("codex", "/project/.codex/skills/review", SkillScope.PROJECT, false, "fp-codex"),
                ),
                compatibleAgents = setOf("claude", "codex"),
                consistency = SkillConsistency.DIFFERENT,
            ),
        ),
        mcpServers = listOf(
            McpServer(
                id = "mcp-1",
                name = "playwright",
                transport = McpTransport.STDIO,
                command = "secret-command",
                args = listOf("--token=secret-token"),
                url = "https://secret.test/mcp",
                environmentVariableNames = setOf("API_TOKEN"),
                sources = listOf(McpSource("claude", "/project/.mcp.json", "playwright", projectName = "LlmBrains")),
                scope = McpScope.PROJECT,
                consistency = McpConsistency.SINGLE_SOURCE,
            ),
        ),
        instructions = listOf(
            InstructionSource(
                path = "/project/CLAUDE.md",
                scope = InstructionScope.PROJECT,
                agentIds = setOf("claude"),
                type = InstructionType.CLAUDE_MD,
                projectName = "LlmBrains",
            ),
        ),
        warnings = listOf(
            EnvironmentWarning("skill", "claude", "project", "Discovery failed: IOException"),
        ),
    )
}
