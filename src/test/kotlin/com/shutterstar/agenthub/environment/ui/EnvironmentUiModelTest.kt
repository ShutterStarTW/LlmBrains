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
    fun `project environment agent filter combines with type and status`() {
        val comparison = EnvironmentComparison(
            agents = listOf(ComparisonAgent("claude", "Claude Code"), ComparisonAgent("codex", "Codex")),
            rows = listOf(
                ComparisonRow("Skill", "shared", setOf("claude", "codex"), shared = true),
                ComparisonRow("Skill", "claude-only", setOf("claude")),
                ComparisonRow("MCP", "codex-only", setOf("codex")),
            ),
        )

        assertEquals(
            listOf("shared", "codex-only"),
            EnvironmentUiModel.filterRows(comparison, "All", SkillFilter.ALL, ScopeFilter.ALL, "codex").map { it.name },
        )
        assertEquals(
            listOf("shared"),
            EnvironmentUiModel.filterRows(comparison, "Skill", SkillFilter.SHARED, ScopeFilter.ALL, "codex").map { it.name },
        )
    }

    @Test
    fun `project environment scope filter separates global and project items`() {
        val base = environment()
        val globalSkill = base.skills.single().copy(
            identity = SkillIdentity("global-skill"),
            name = "global-review",
            scope = SkillScope.GLOBAL,
            sources = listOf(SkillSource("claude", "/home/.claude/skills/global-review", SkillScope.GLOBAL, false, "fp")),
        )
        val comparison = EnvironmentUiModel.comparison(base.copy(skills = base.skills + globalSkill)) { it }

        val global = EnvironmentUiModel.filterRows(comparison, "All", SkillFilter.ALL, ScopeFilter.GLOBAL, null)
        val project = EnvironmentUiModel.filterRows(comparison, "All", SkillFilter.ALL, ScopeFilter.PROJECT, null)

        assertTrue(global.any { it.category == "Skill" && it.name == "global-review (Global)" })
        assertTrue(global.any { it.category == "MCP" })
        assertTrue(global.all { it.scope == "Global" })
        assertTrue(project.any { it.category == "Skill" && it.name == "review" })
        assertTrue(project.any { it.category == "Instruction" })
        assertTrue(project.all { it.scope == "Project" })
    }

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

        val comparison = EnvironmentUiModel.agentComparison(agentEnvironment, "codex")
        val skill = comparison.rows.single { it.category == "Skill" }
        val mcp = comparison.rows.single { it.category == "MCP" }

        assertTrue(comparison.agents.isEmpty(), "agent context has no agent column")
        assertTrue(comparison.rows.all { it.agentIds.isEmpty() })
        assertTrue(skill.detail!!.contains("Different contents"))
        assertTrue(skill.conflict)
        assertEquals("/project/.codex/skills/review", skill.sourcePath)
        assertEquals(project.skills.single().identity.id, skill.skillId)
        assertEquals(SkillScope.PROJECT, skill.skillScope)
        assertFalse(listOf(skill.name, skill.detail, skill.location).any { it!!.contains("Claude") })
        assertTrue(mcp.detail!!.contains("Different configuration"))
        assertEquals("/home/.codex/config.toml", mcp.sourcePath)
        assertFalse(comparison.rows.any { row -> listOfNotNull(row.name, row.detail).any { it.contains("secret-command") || it.contains("https://secret.test") } })
        assertFalse(listOf(mcp.name, mcp.detail, mcp.location).any { it!!.contains("Claude") })
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

        val rows = EnvironmentUiModel.agentComparison(agentEnvironment, "claude").rows

        val skillRows = rows.filter { it.category == "Skill" }
        assertEquals(1, skillRows.size)
        assertEquals("/project/.claude/skills/review", skillRows.single().sourcePath)
        assertEquals(1, rows.count { it.category == "MCP" })
    }

    @Test
    fun `agent rows carry shared and conflict flags for the Status filter`() {
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

        val all = EnvironmentUiModel.agentComparison(agentEnvironment, "codex").rows.filter { it.category == "Skill" }

        assertEquals(2, all.size)
        assertEquals(listOf("shared-skill"), all.filter { it.shared }.map { it.name })
        assertEquals(listOf(conflicting.name), all.filter { it.conflict }.map { it.name })
        assertTrue(EnvironmentUiModel.detailLines(all.single { it.shared }).any { it.contains("Shared source") })
        assertEquals("Different contents", all.single { it.conflict }.detail)
    }

    @Test
    fun `agent rows show one skill when an agent directory aliases a shared skill`() {
        val sharedPath = "C:/Users/example/.agents/skills/magyar-humanizer"
        val skill = environment().skills.single().copy(
            identity = SkillIdentity("magyar-humanizer"),
            name = "magyar-humanizer",
            scope = SkillScope.GLOBAL,
            consistency = SkillConsistency.IDENTICAL,
            sources = listOf(
                SkillSource(null, sharedPath, SkillScope.GLOBAL, true, "fp", realPath = sharedPath),
                SkillSource(
                    "kiro",
                    "C:/Users/example/.kiro/skills/magyar-humanizer",
                    SkillScope.GLOBAL,
                    false,
                    "fp",
                    realPath = sharedPath,
                ),
            ),
        )
        val agentEnvironment = AgentEnvironment("kiro", listOf(skill), emptyList(), emptyList())

        assertEquals(1, EnvironmentUiModel.agentSummary(agentEnvironment).globalSkillCount)
        val rows = EnvironmentUiModel.agentComparison(agentEnvironment, "kiro").rows
        assertEquals(1, rows.size)
        assertEquals("C:/Users/example/.kiro/skills/magyar-humanizer", rows.single().sourcePath)
        assertFalse(rows.single().shared)
    }

    @Test
    fun `agent rows collapse a shared skill and a native alias when shared skills are supported`() {
        val sharedPath = "C:/Users/example/.agents/skills/review"
        val skill = environment().skills.single().copy(
            scope = SkillScope.GLOBAL,
            sources = listOf(
                SkillSource(null, sharedPath, SkillScope.GLOBAL, true, "fp", realPath = sharedPath),
                SkillSource("codex", "C:/Users/example/.codex/skills/review", SkillScope.GLOBAL, false, "fp", realPath = sharedPath),
            ),
        )
        val agentEnvironment = AgentEnvironment("codex", listOf(skill), emptyList(), emptyList())

        val rows = EnvironmentUiModel.agentComparison(agentEnvironment, "codex").rows
        assertEquals(1, rows.size)
        assertEquals(sharedPath, rows.single().sourcePath)
        assertTrue(rows.single().shared)
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

        val row = EnvironmentUiModel.agentComparison(agentEnvironment, "codex").rows.single()

        assertEquals("Review Skill", row.name)
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

        val rows = EnvironmentUiModel.agentComparison(agentEnvironment, "claude").rows

        val projectRow = rows.single { it.sourcePath == projectInstruction.path }
        assertEquals("AGENTS.md", projectRow.name)
        assertEquals("LlmBrains", projectRow.location)
        assertEquals("Project", projectRow.scope)
        val globalRow = rows.single { it.sourcePath == globalInstruction.path }
        assertEquals("CLAUDE.md", globalRow.name)
        assertEquals("Global", globalRow.location)
        assertEquals("Global", globalRow.scope)
        assertTrue(rows.all { it.agentIds.isEmpty() })
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
    fun `agent warnings surface capability scope and message without naming the agent or raw exception text`() {
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

        val comparison = EnvironmentUiModel.agentComparison(agentEnvironment, "codex")

        assertTrue(comparison.rows.isEmpty(), "a warning is not a table row")
        val warning = comparison.warnings.single()
        assertTrue(warning.contains("MCP · Project"))
        assertFalse(warning.contains("Codex"), "agent context never repeats the agent name")
        assertTrue(warning.contains("IllegalStateException"))
    }

    @Test
    fun `agent warning without an agent id is labelled as shared`() {
        val agentEnvironment = AgentEnvironment(
            agentId = "codex",
            skills = emptyList(),
            mcpServers = emptyList(),
            instructions = emptyList(),
            warnings = listOf(EnvironmentWarning(capability = "skills", agentId = null, scope = "global", message = "Unreadable")),
        )

        val warning = EnvironmentUiModel.agentComparison(agentEnvironment, "codex").warnings.single()

        assertEquals("Skills · Global · Shared · Unreadable", warning)
    }

    @Test
    fun `project warnings name the agent and stay out of the table rows`() {
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

        assertTrue(comparison.rows.none { it.category == "Warning" })
        assertEquals(listOf("Codex · MCP · Project · Discovery failed: IOException"), comparison.warnings)
    }

    @Test
    fun `detail lines list path then scope and consistency without agent ownership`() {
        val row = ComparisonRow(
            category = "MCP",
            name = "docs (Project)",
            agentIds = setOf("claude", "codex"),
            scope = "Project",
            sourcePath = "/project/.mcp.json",
            detail = "STDIO · Consistent",
        )

        assertEquals(
            listOf("/project/.mcp.json", "Scope: Project · STDIO · Consistent"),
            EnvironmentUiModel.detailLines(row),
        )
    }

    @Test
    fun `detail lines are empty for a row without any metadata`() {
        val row = ComparisonRow(category = "Skill", name = "x", agentIds = emptySet())

        assertTrue(EnvironmentUiModel.detailLines(row).isEmpty())
    }

    @Test
    fun `should build an agent capability comparison without MCP connection values`() {
        val comparison = EnvironmentUiModel.comparison(environment()) { it.replaceFirstChar(Char::uppercase) }

        assertEquals(listOf("Claude", "Codex"), comparison.agents.map { it.name })
        assertEquals(3, comparison.rows.size)
        assertEquals(setOf("claude", "codex"), comparison.rows.first { it.category == "Skill" }.agentIds)
        assertEquals(environment().skills.single().identity.id, comparison.rows.first { it.category == "Skill" }.skillId)
        assertEquals(setOf("claude", "codex"), comparison.rows.first { it.category == "MCP" }.agentIds)
        assertFalse(comparison.rows.any { it.name.contains("secret-command") || it.detail.orEmpty().contains("secret-command") })
        assertFalse(comparison.rows.any { it.name.contains("https://secret.test") || it.detail.orEmpty().contains("https://secret.test") })
        assertEquals("Project", comparison.rows.first { it.category == "Skill" }.scope)
        assertEquals("/project/AGENTS.md", comparison.rows.first { it.category == "Instruction" }.sourcePath)
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

    @Test
    fun `comparison instruction rows show the full filename and mark only global ones`() {
        val base = environment()
        val nested = base.copy(
            instructions = listOf(
                InstructionSource(
                    path = "/project/packages/api/AGENTS.md",
                    scope = InstructionScope.PROJECT,
                    agentIds = setOf("codex"),
                    type = InstructionType.AGENTS_MD,
                ),
                InstructionSource(
                    path = "/project/packages/web/AGENTS.md",
                    scope = InstructionScope.PROJECT,
                    agentIds = setOf("codex"),
                    type = InstructionType.AGENTS_MD,
                ),
            ),
        )

        val comparison = EnvironmentUiModel.comparison(nested) { it.replaceFirstChar(Char::uppercase) }
        val names = comparison.rows.filter { it.category == "Instruction" }.map { it.name }

        assertEquals(listOf("AGENTS.md", "AGENTS.md"), names)
    }

    @Test
    fun `comparison instruction row falls back to the filename without a project root`() {
        val comparison = EnvironmentUiModel.comparison(environment()) { it.replaceFirstChar(Char::uppercase) }
        val name = comparison.rows.single { it.category == "Instruction" }.name

        assertEquals("AGENTS.md", name)
    }

    @Test
    fun `config rows are named by file name and only global ones are marked in project context`() {
        fun config(path: String, scope: com.shutterstar.agenthub.environment.config.model.ConfigScope) =
            com.shutterstar.agenthub.environment.config.model.AgentConfigSource(
                agentId = "claude",
                path = path,
                scope = scope,
                kind = com.shutterstar.agenthub.environment.config.model.ConfigKind.SETTINGS,
                format = com.shutterstar.agenthub.environment.config.model.ConfigFormat.JSON,
            )
        val withConfigs = environment().copy(
            configs = listOf(
                config("/home/.claude/settings.json", com.shutterstar.agenthub.environment.config.model.ConfigScope.GLOBAL),
                config("/project/.claude/settings.local.json", com.shutterstar.agenthub.environment.config.model.ConfigScope.PROJECT),
            ),
        )

        val projectNames = EnvironmentUiModel.comparison(withConfigs) { it }.rows
            .filter { it.category == "Config" }.map { it.name }
        assertEquals(listOf("settings.json (Global)", "settings.local.json"), projectNames)

        val agentNames = EnvironmentUiModel.agentComparison(
            AgentEnvironment("claude", emptyList(), emptyList(), emptyList(), configs = withConfigs.configs),
            "claude",
        ).rows.filter { it.category == "Config" }.map { it.name }
        assertEquals(listOf("settings.json", "settings.local.json"), agentNames)
    }

    @Test
    fun `instruction rows show the per-agent note of a listed file`() {
        val base = environment()
        val unused = base.instructions.single().copy(agentNotes = mapOf("claude" to "CLAUDE.md takes precedence."))

        val projectRow = EnvironmentUiModel.comparison(base.copy(instructions = listOf(unused))) { it.replaceFirstChar(Char::uppercase) }
            .rows.single { it.category == "Instruction" }
        assertEquals(listOf("Claude: CLAUDE.md takes precedence."), projectRow.extraDetailLines)
        assertTrue(EnvironmentUiModel.detailLines(projectRow).contains("Claude: CLAUDE.md takes precedence."))

        val agentEnvironment = AgentEnvironment("claude", emptyList(), emptyList(), listOf(unused))
        val agentRow = EnvironmentUiModel.agentComparison(agentEnvironment, "claude").rows.single()
        assertEquals(listOf("CLAUDE.md takes precedence."), agentRow.extraDetailLines)
        val otherAgentRow = EnvironmentUiModel.agentComparison(agentEnvironment, "codex").rows.single()
        assertTrue(otherAgentRow.extraDetailLines.isEmpty())
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
