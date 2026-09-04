package com.shutterstar.agenthub.environment.ui

import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.environment.mcp.model.McpConsistency
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpServer
import com.shutterstar.agenthub.environment.mcp.model.McpSource
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.environment.model.AgentEnvironment
import com.shutterstar.agenthub.environment.model.EnvironmentWarning
import com.shutterstar.agenthub.environment.model.ProjectEnvironment
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnvironmentUiModelTest {
    @Test
    fun `should summarize capabilities and conflicts`() {
        val summary = EnvironmentUiModel.summary(environment())

        assertEquals(1, summary.skillCount)
        assertEquals(1, summary.skillConflictCount)
        assertEquals(1, summary.mcpServerCount)
        assertEquals(1, summary.mcpConflictCount)
        assertEquals(1, summary.instructionCount)
        assertEquals(0, summary.warningCount)
    }

    @Test
    fun `should create safe readable agent rows scoped to the requesting agent without MCP connection values`() {
        val project = environment()
        val agentEnvironment = AgentEnvironment(
            agentId = "codex",
            skills = project.skills,
            mcpServers = project.mcpServers,
            instructions = project.instructions,
        )

        val skill = EnvironmentUiModel.skillRows(agentEnvironment, "codex").single()
        val mcp = EnvironmentUiModel.mcpRows(agentEnvironment, "codex").single()

        assertTrue(skill.detail.contains("Different contents"))
        assertTrue(skill.detail.contains("/project/.codex/skills/review"))
        assertFalse(skill.detail.contains("/project/.claude/skills/review"))
        assertFalse(skill.detail.contains("Claude"))
        assertTrue(mcp.detail.contains("Different configuration"))
        assertFalse(mcp.detail.contains("secret-command"))
        assertFalse(mcp.detail.contains("https://secret.test"))
        assertFalse(mcp.detail.contains("Claude"))
    }

    @Test
    fun `agent rows avoid duplicate Global and put the project name before the skill scope`() {
        val base = environment()
        val globalSkill = base.skills.single().copy(
            scope = SkillScope.GLOBAL,
            sources = base.skills.single().sources.map { it.copy(scope = SkillScope.GLOBAL, projectName = null) },
        )
        val projectSkill = base.skills.single().copy(
            identity = SkillIdentity("project-skill"),
            name = "project-skill",
            sources = listOf(
                SkillSource("codex", "/project/.codex/skills/project-skill", SkillScope.PROJECT, false, "fp", projectName = "LlmBrains"),
            ),
        )
        val globalMcp = base.mcpServers.single().copy(
            sources = listOf(base.mcpServers.single().sources.first().copy(agentId = "codex", projectName = null)),
        )
        val agentEnvironment = AgentEnvironment(
            agentId = "codex",
            skills = listOf(globalSkill, projectSkill),
            mcpServers = listOf(globalMcp),
            instructions = emptyList(),
        )

        val skillRows = EnvironmentUiModel.skillRows(agentEnvironment, "codex")
        val mcpRows = EnvironmentUiModel.mcpRows(agentEnvironment, "codex")

        val globalSkillRow = skillRows.single { it.title == "review" }
        assertFalse(globalSkillRow.detail.contains("Global · Global"))
        assertTrue(globalSkillRow.detail.startsWith("Global ·"))
        val projectSkillRow = skillRows.single { it.title == "project-skill" }
        assertTrue(projectSkillRow.detail.startsWith("LlmBrains · Project ·"))
        val globalMcpRow = mcpRows.single()
        assertFalse(globalMcpRow.detail.contains("Global · Global"))
    }

    @Test
    fun `agent skill and MCP rows only include this agent's own and shared sources`() {
        val base = environment()
        val agentEnvironment = AgentEnvironment(
            agentId = "claude",
            skills = base.skills,
            mcpServers = base.mcpServers,
            instructions = emptyList(),
        )

        val skillRows = EnvironmentUiModel.skillRows(agentEnvironment, "claude")
        val mcpRows = EnvironmentUiModel.mcpRows(agentEnvironment, "claude")

        assertEquals(1, skillRows.size)
        assertTrue(skillRows.single().detail.contains("/project/.claude/skills/review"))
        assertEquals(1, mcpRows.size)
    }

    @Test
    fun `skill filter narrows agent rows to shared or conflicting skills`() {
        val base = environment()
        val conflicting = base.skills.single()
        val shared = conflicting.copy(
            identity = SkillIdentity("shared-skill"),
            name = "shared-skill",
            consistency = SkillConsistency.IDENTICAL,
            sources = listOf(SkillSource(null, "/shared/skills/shared-skill", SkillScope.GLOBAL, true, "fp")),
        )
        val agentEnvironment = AgentEnvironment(
            agentId = "codex",
            skills = listOf(conflicting, shared),
            mcpServers = emptyList(),
            instructions = emptyList(),
        )

        val all = EnvironmentUiModel.skillRows(agentEnvironment, "codex", SkillFilter.ALL)
        val sharedOnly = EnvironmentUiModel.skillRows(agentEnvironment, "codex", SkillFilter.SHARED)
        val conflictsOnly = EnvironmentUiModel.skillRows(agentEnvironment, "codex", SkillFilter.CONFLICTS)

        assertEquals(2, all.size)
        assertEquals(listOf("shared-skill"), sharedOnly.map { it.title })
        assertEquals(listOf(conflicting.name), conflictsOnly.map { it.title })
    }

    @Test
    fun `agent skill rows use the SKILL md heading as title falling back to the frontmatter name`() {
        val base = environment()
        val withHeading = base.skills.single().copy(
            sources = listOf(
                SkillSource(
                    "codex",
                    "/project/.codex/skills/review",
                    SkillScope.PROJECT,
                    false,
                    "fp",
                    displayTitle = "Review Skill",
                ),
            ),
        )
        val agentEnvironment = AgentEnvironment(
            agentId = "codex",
            skills = listOf(withHeading),
            mcpServers = emptyList(),
            instructions = emptyList(),
        )

        val row = EnvironmentUiModel.skillRows(agentEnvironment, "codex").single()

        assertEquals("Review Skill", row.title)
    }

    @Test
    fun `agent level instruction rows show the project name and file without scope or agent list`() {
        val base = environment()
        val projectInstruction = base.instructions.single().copy(projectName = "LlmBrains")
        val globalInstruction = projectInstruction.copy(
            path = "/home/.claude/CLAUDE.md",
            scope = InstructionScope.GLOBAL,
            projectName = null,
        )
        val agentEnvironment = AgentEnvironment(
            agentId = "claude",
            skills = emptyList(),
            mcpServers = emptyList(),
            instructions = listOf(projectInstruction, globalInstruction),
        )

        val rows = EnvironmentUiModel.instructionRows(agentEnvironment)

        val projectRow = rows.single { it.detail == projectInstruction.path }
        assertEquals("LlmBrains — AGENTS.md", projectRow.title)
        val globalRow = rows.single { it.detail == globalInstruction.path }
        assertEquals("Global — CLAUDE.md", globalRow.title)
    }

    @Test
    fun `should split global and project skill counts for an agent`() {
        val project = environment()
        val globalSkill = project.skills.single().copy(scope = SkillScope.GLOBAL)
        val agentEnvironment = AgentEnvironment(
            agentId = "codex",
            skills = listOf(globalSkill, project.skills.single()),
            mcpServers = project.mcpServers,
            instructions = project.instructions,
        )

        val summary = EnvironmentUiModel.agentSummary(agentEnvironment)

        assertEquals(1, summary.globalSkillCount)
        assertEquals(1, summary.projectSkillCount)
        assertEquals(1, summary.mcpServerCount)
        assertEquals(1, summary.instructionCount)
    }

    @Test
    fun `warning rows surface capability agent and message without raw exception text`() {
        val agentEnvironment = AgentEnvironment(
            agentId = "codex",
            skills = emptyList(),
            mcpServers = emptyList(),
            instructions = emptyList(),
            warnings = listOf(
                EnvironmentWarning(
                    capability = "mcp",
                    agentId = "codex",
                    scope = "project",
                    message = "Discovery failed: IllegalStateException",
                ),
            ),
        )

        val warning = EnvironmentUiModel.warningRows(agentEnvironment) { it.replaceFirstChar(Char::uppercase) }.single()

        assertTrue(warning.title.contains("MCP"))
        assertTrue(warning.title.contains("Codex"))
        assertTrue(warning.detail.contains("IllegalStateException"))
    }

    @Test
    fun `project comparison includes discovery warnings`() {
        val environment = environment().copy(
            warnings = listOf(
                EnvironmentWarning(
                    capability = "mcp",
                    agentId = "codex",
                    scope = "project",
                    message = "Discovery failed: IOException",
                ),
            ),
        )

        val comparison = EnvironmentUiModel.comparison(environment) { it.replaceFirstChar(Char::uppercase) }
        val warning = comparison.rows.single { it.category == "Warning" }

        assertEquals(setOf("codex"), warning.agentIds)
        assertTrue(warning.name.contains("MCP · Project"))
        assertTrue(warning.name.contains("IOException"))
    }

    @Test
    fun `should build an agent capability comparison without MCP connection values`() {
        val comparison = EnvironmentUiModel.comparison(environment()) { it.replaceFirstChar(Char::uppercase) }

        assertEquals(listOf("Claude", "Codex"), comparison.agents.map { it.name })
        assertEquals(3, comparison.rows.size)
        assertEquals(setOf("claude", "codex"), comparison.rows.first { it.category == "Skill" }.agentIds)
        assertEquals(setOf("claude", "codex"), comparison.rows.first { it.category == "MCP" }.agentIds)
        assertFalse(comparison.rows.any { it.name.contains("secret-command") })
        assertFalse(comparison.rows.any { it.name.contains("https://secret.test") })
    }

    @Test
    fun `comparison skill row uses the SKILL md heading as its display name`() {
        val base = environment()
        val withHeading = base.copy(
            skills = listOf(
                base.skills.single().copy(
                    sources = base.skills.single().sources.map { it.copy(displayTitle = "Review Skill") },
                ),
            ),
        )

        val comparison = EnvironmentUiModel.comparison(withHeading) { it.replaceFirstChar(Char::uppercase) }

        assertTrue(comparison.rows.single { it.category == "Skill" }.name.startsWith("Review Skill"))
    }

    @Test
    fun `should include Antigravity and Copilot columns in comparison`() {
        val base = environment()
        val environment = base.copy(
            agentIds = setOf("antigravity", "copilot"),
            skills = listOf(
                base.skills.single().copy(compatibleAgents = setOf("antigravity", "copilot")),
            ),
            mcpServers = listOf(
                base.mcpServers.single().copy(
                    sources = listOf(
                        McpSource("antigravity", "/home/.gemini/config/mcp_config.json", "playwright"),
                        McpSource("copilot", "/home/.copilot/mcp-config.json", "playwright"),
                    ),
                ),
            ),
            instructions = listOf(
                base.instructions.single().copy(agentIds = setOf("antigravity", "copilot")),
            ),
        )

        val comparison = EnvironmentUiModel.comparison(environment) { it.replaceFirstChar(Char::uppercase) }

        assertEquals(listOf("Antigravity", "Copilot"), comparison.agents.map { it.name })
        assertTrue(comparison.rows.all { it.agentIds == setOf("antigravity", "copilot") })
    }

    private fun environment(): ProjectEnvironment = ProjectEnvironment(
        projectId = "project",
        agentIds = setOf("claude", "codex"),
        skills = listOf(
            AgentSkill(
                identity = SkillIdentity("skill"),
                name = "review",
                description = null,
                scope = SkillScope.PROJECT,
                sources = listOf(
                    SkillSource("claude", "/project/.claude/skills/review", SkillScope.PROJECT, false, "fingerprint-claude"),
                    SkillSource("codex", "/project/.codex/skills/review", SkillScope.PROJECT, false, "fingerprint-codex"),
                ),
                compatibleAgents = setOf("claude", "codex"),
                consistency = SkillConsistency.DIFFERENT,
            ),
        ),
        mcpServers = listOf(
            McpServer(
                id = "mcp",
                name = "playwright",
                transport = McpTransport.STDIO,
                command = "secret-command",
                args = listOf("secret-argument"),
                url = "https://secret.test",
                environmentVariableNames = setOf("TOKEN"),
                sources = listOf(
                    McpSource("claude", "/home/.claude.json", "playwright"),
                    McpSource("codex", "/home/.codex/config.toml", "playwright"),
                ),
                scope = McpScope.GLOBAL,
                consistency = McpConsistency.DIFFERENT,
            ),
        ),
        instructions = listOf(
            InstructionSource(
                path = "/project/AGENTS.md",
                scope = InstructionScope.PROJECT,
                agentIds = setOf("codex"),
                type = InstructionType.AGENTS_MD,
            ),
        ),
    )
}
