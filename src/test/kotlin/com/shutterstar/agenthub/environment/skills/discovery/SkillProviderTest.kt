package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SkillProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should discover global and project shared skills`() {
        writeSkill(
            temporaryDirectory.resolve(".agents/skills/global-review"),
            """
            ---
            name: global-review
            description: Reviews global changes
            ---
            Instructions
            """.trimIndent(),
        )
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"))
        writeSkill(
            projectRoot.resolve(".agents/skills/project-review"),
            """
            ---
            name: project-review
            description: Reviews this project
            ---
            Instructions
            """.trimIndent(),
        )
        writeSkill(
            projectRoot.resolve("apps/web/.agents/skills/nested-review"),
            """
            ---
            name: nested-review
            description: Reviews the web package
            ---
            Instructions
            """.trimIndent(),
        )

        val provider = SharedSkillProvider(temporaryDirectory)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(projectRoot))

        assertEquals("global-review", global.name)
        assertEquals(SkillScope.GLOBAL, global.scope)
        assertTrue(global.shared)
        assertNull(global.agentId)
        assertEquals(setOf("project-review", "nested-review"), project.mapTo(mutableSetOf()) { it.name })
        assertTrue(project.all { it.scope == SkillScope.PROJECT && it.shared })
    }

    @Test
    fun `should discover Claude and legacy Codex skill locations`() {
        writeSkill(temporaryDirectory.resolve(".claude/skills/claude-review"), "---\nname: claude-review\n---")
        writeSkill(temporaryDirectory.resolve(".codex/skills/codex-review"), "---\nname: codex-review\n---")

        val claude = ClaudeSkillProvider(temporaryDirectory).discoverGlobal().single()
        val codex = CodexSkillProvider(temporaryDirectory).discoverGlobal().single()

        assertEquals("claude", claude.agentId)
        assertEquals("claude-review", claude.name)
        assertEquals("codex", codex.agentId)
        assertEquals("codex-review", codex.name)
    }

    @Test
    fun `should discover nested Cursor and OpenCode skill locations`() {
        writeSkill(
            temporaryDirectory.resolve(".cursor/skills/team/cursor-review"),
            "---\nname: cursor-review\ndescription: Reviews Cursor changes\n---",
        )
        writeSkill(
            temporaryDirectory.resolve(".config/opencode/skills/team/opencode-review"),
            validSkill("opencode-review", "Reviews OpenCode changes"),
        )

        val cursor = CursorSkillProvider(temporaryDirectory).discoverGlobal().single()
        val openCode = OpenCodeSkillProvider(temporaryDirectory).discoverGlobal().single()

        assertEquals("cursor", cursor.agentId)
        assertEquals("cursor-review", cursor.name)
        assertEquals("opencode", openCode.agentId)
        assertEquals("opencode-review", openCode.name)
    }

    @Test
    fun `should discover OpenCode native and Claude compatible skills`() {
        writeSkill(
            temporaryDirectory.resolve(".claude/skills/global-compat"),
            validSkill("global-compat", "Global OpenCode-compatible skill"),
        )
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("opencode-project"))
        writeSkill(
            projectRoot.resolve("packages/app/.opencode/skills/native-project"),
            validSkill("native-project", "Nested native OpenCode skill"),
        )
        writeSkill(
            projectRoot.resolve("packages/lib/.claude/skills/project-compat"),
            validSkill("project-compat", "Project OpenCode-compatible skill"),
        )

        val provider = OpenCodeSkillProvider(temporaryDirectory)

        assertEquals(setOf("global-compat"), provider.discoverGlobal().mapTo(mutableSetOf()) { it.name })
        assertEquals(
            setOf("native-project", "project-compat"),
            provider.discoverProject(project(projectRoot)).mapTo(mutableSetOf()) { it.name },
        )
    }

    @Test
    fun `should discover Cursor native managed and compatibility global skills`() {
        writeSkill(
            temporaryDirectory.resolve(".cursor/skills/native-review"),
            validSkill("native-review", "Reviews native Cursor changes"),
        )
        writeSkill(
            temporaryDirectory.resolve(".cursor/skills-cursor/managed-review"),
            """
            ---
            name: managed-review
            description: >-
              Reviews managed
              Cursor changes
            ---
            """.trimIndent(),
        )
        writeSkill(
            temporaryDirectory.resolve(".claude/skills/claude-review"),
            validSkill("claude-review", "Cursor-compatible Claude skill"),
        )
        writeSkill(
            temporaryDirectory.resolve(".codex/skills/codex-review"),
            validSkill("codex-review", "Cursor-compatible Codex skill"),
        )

        val skills = CursorSkillProvider(temporaryDirectory).discoverGlobal()

        assertEquals(
            setOf("native-review", "managed-review", "claude-review", "codex-review"),
            skills.mapTo(mutableSetOf()) { it.name },
        )
        assertTrue(skills.all { it.agentId == "cursor" && it.scope == SkillScope.GLOBAL })
        assertEquals(
            "Reviews managed Cursor changes",
            skills.single { it.name == "managed-review" }.description,
        )
    }

    @Test
    fun `should discover nested and compatibility Cursor project skills`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("cursor-project"))
        writeSkill(
            projectRoot.resolve(".cursor/skills/root-review"),
            validSkill("root-review", "Reviews the repository"),
        )
        writeSkill(
            projectRoot.resolve("apps/web/.cursor/skills/web-review"),
            validSkill("web-review", "Reviews the web package"),
        )
        writeSkill(
            projectRoot.resolve(".claude/skills/claude-review"),
            validSkill("claude-review", "Cursor-compatible Claude skill"),
        )
        writeSkill(
            projectRoot.resolve(".codex/skills/codex-review"),
            validSkill("codex-review", "Cursor-compatible Codex skill"),
        )
        val project = project(projectRoot).copy(name = "Cursor Project")

        val skills = CursorSkillProvider(temporaryDirectory).discoverProject(project)

        assertEquals(
            setOf("root-review", "web-review", "claude-review", "codex-review"),
            skills.mapTo(mutableSetOf()) { it.name },
        )
        assertTrue(skills.all { it.agentId == "cursor" && it.projectName == "Cursor Project" })
    }

    @Test
    fun `should ignore Cursor skills with invalid required metadata`() {
        writeSkill(
            temporaryDirectory.resolve(".cursor/skills/missing-description"),
            "---\nname: missing-description\n---",
        )
        writeSkill(
            temporaryDirectory.resolve(".cursor/skills/folder-name"),
            validSkill("different-name", "Name does not match its directory"),
        )
        writeSkill(
            temporaryDirectory.resolve(".cursor/skills/Valid_Name"),
            validSkill("Valid_Name", "Name does not use the supported format"),
        )
        writeSkill(
            temporaryDirectory.resolve(".cursor/skills/valid-skill"),
            validSkill("valid-skill", "Valid Cursor skill"),
        )

        val skill = CursorSkillProvider(temporaryDirectory).discoverGlobal().single()

        assertEquals("valid-skill", skill.name)
    }

    @Test
    fun `should discover a Cursor skill linked from its skills directory`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val target = temporaryDirectory.resolve("linked-target")
        writeSkill(target, validSkill("linked-skill", "Linked Cursor skill"))
        val skillsRoot = Files.createDirectories(temporaryDirectory.resolve(".cursor/skills"))
        Files.createSymbolicLink(skillsRoot.resolve("linked-skill"), target)

        val skill = CursorSkillProvider(temporaryDirectory).discoverGlobal().single()

        assertEquals("linked-skill", skill.name)
    }

    @Test
    fun `should discover default custom and root local plugin skills`() {
        val agentPlugin = Files.createDirectories(temporaryDirectory.resolve(".cursor/plugins/local/agent-plugin"))
        Files.writeString(
            agentPlugin.resolve("plugin.json"),
            """{"${'$'}schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"agent-plugin"}""",
        )
        writeSkill(
            agentPlugin.resolve("skills/agent-skill"),
            validSkill("agent-skill", "Agent plugin skill"),
        )

        val customPlugin = Files.createDirectories(temporaryDirectory.resolve(".cursor/plugins/local/custom-plugin"))
        Files.createDirectories(customPlugin.resolve(".cursor-plugin"))
        Files.writeString(
            customPlugin.resolve(".cursor-plugin/plugin.json"),
            """{"name":"custom-plugin","skills":"custom-skills"}""",
        )
        writeSkill(
            customPlugin.resolve("custom-skills/custom-skill"),
            validSkill("custom-skill", "Custom Cursor plugin skill"),
        )

        val rootPlugin = Files.createDirectories(temporaryDirectory.resolve(".cursor/plugins/local/root-plugin"))
        Files.createDirectories(rootPlugin.resolve(".cursor-plugin"))
        Files.writeString(rootPlugin.resolve(".cursor-plugin/plugin.json"), """{"name":"root-plugin"}""")
        Files.writeString(rootPlugin.resolve("SKILL.md"), validSkill("root-plugin", "Root Cursor plugin skill"))

        val skills = CursorSkillProvider(temporaryDirectory).discoverGlobal()

        assertEquals(
            setOf("agent-skill", "custom-skill", "root-plugin"),
            skills.mapTo(mutableSetOf()) { it.name },
        )
    }

    @Test
    fun `nested skill discovery shares one scan budget across roots`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("budget-project"))
        writeSkill(projectRoot.resolve(".cursor/skills/first"), validSkill("first", "First skill"))
        writeSkill(
            projectRoot.resolve("package/.cursor/skills/second"),
            validSkill("second", "Second skill"),
        )
        repeat(20) { index ->
            Files.writeString(projectRoot.resolve("filler-$index.txt"), "fixture")
        }

        val skills = SkillDirectoryScanner(maximumScanEntries = 8).discoverNestedProjectSkills(
            projectRoot = projectRoot,
            ownerDirectoryName = ".cursor",
            agentId = "cursor",
            shared = false,
            projectName = "Budget Project",
            requireValidMetadata = true,
        )

        assertTrue(skills.size <= 1)
    }

    @Test
    fun `should discover Antigravity global CLI and legacy project skills`() {
        writeSkill(
            temporaryDirectory.resolve(".gemini/config/skills/global-review"),
            "---\nname: global-review\n---",
        )
        writeSkill(
            temporaryDirectory.resolve(".gemini/antigravity-cli/skills/cli-review"),
            "---\nname: cli-review\n---",
        )
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("antigravity-project"))
        writeSkill(projectRoot.resolve(".agent/skills/legacy-review"), "---\nname: legacy-review\n---")

        val provider = AntigravitySkillProvider(temporaryDirectory)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(projectRoot)).single()

        assertEquals(setOf("global-review", "cli-review"), global.mapTo(mutableSetOf()) { it.name })
        assertTrue(global.all { it.agentId == "antigravity" && it.scope == SkillScope.GLOBAL })
        assertEquals("legacy-review", project.name)
        assertEquals("antigravity", project.agentId)
    }

    @Test
    fun `should discover Antigravity direct skills, builtin skills, alt project skills and plugin skills`() {
        writeSkill(
            temporaryDirectory.resolve(".gemini/skills/direct-user-skill"),
            "---\nname: direct-user-skill\n---",
        )
        writeSkill(
            temporaryDirectory.resolve(".gemini/antigravity-cli/builtin/skills/builtin-skill"),
            "---\nname: builtin-skill\n---",
        )
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("antigravity-full-project"))
        writeSkill(projectRoot.resolve("_agents/skills/alt-agents-skill"), "---\nname: alt-agents-skill\n---")
        writeSkill(projectRoot.resolve(".gemini/skills/project-gemini-skill"), "---\nname: project-gemini-skill\n---")
        writeSkill(
            projectRoot.resolve(".agents/plugins/dev-kit/skills/plugin-skill"),
            "---\nname: plugin-skill\n---",
        )

        val provider = AntigravitySkillProvider(temporaryDirectory)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(projectRoot))

        assertEquals(
            setOf("direct-user-skill", "builtin-skill"),
            global.mapTo(mutableSetOf()) { it.name },
        )
        assertTrue(global.all { it.agentId == "antigravity" && it.scope == SkillScope.GLOBAL })
        assertEquals(
            setOf("alt-agents-skill", "project-gemini-skill", "plugin-skill"),
            project.mapTo(mutableSetOf()) { it.name },
        )
        assertTrue(project.all { it.agentId == "antigravity" && it.scope == SkillScope.PROJECT })
    }

    @Test
    fun `should discover Copilot personal and compatible project skills`() {
        val copilotHome = temporaryDirectory.resolve(".copilot")
        writeSkill(copilotHome.resolve("skills/personal-review"), "---\nname: personal-review\n---")
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("copilot-project"))
        writeSkill(projectRoot.resolve(".github/skills/github-review"), "---\nname: github-review\n---")
        writeSkill(projectRoot.resolve(".claude/skills/claude-review"), "---\nname: claude-review\n---")

        val provider = CopilotSkillProvider(copilotHome)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(projectRoot))

        assertEquals("personal-review", global.name)
        assertEquals("copilot", global.agentId)
        assertEquals(setOf("github-review", "claude-review"), project.mapTo(mutableSetOf()) { it.name })
        assertTrue(project.all { it.agentId == "copilot" && it.scope == SkillScope.PROJECT })
    }

    @Test
    fun `should ignore missing SKILL md and unexpected files`() {
        val skillsRoot = Files.createDirectories(temporaryDirectory.resolve(".agents/skills"))
        Files.createDirectories(skillsRoot.resolve("missing-entrypoint"))
        Files.writeString(skillsRoot.resolve("unexpected.md"), "not a skill directory")
        Files.createDirectories(skillsRoot.resolve("assets"))
        Files.writeString(skillsRoot.resolve("assets/README.md"), "not a skill")

        assertTrue(SharedSkillProvider(temporaryDirectory).discoverGlobal().isEmpty())
    }

    @Test
    fun `should tolerate malformed and empty skill metadata`() {
        writeSkill(
            temporaryDirectory.resolve(".claude/skills/malformed"),
            "---\nname: never-closed\ndescription: malformed",
        )
        writeSkill(temporaryDirectory.resolve(".claude/skills/empty-skill"), "")

        val skills = ClaudeSkillProvider(temporaryDirectory).discoverGlobal().associateBy { it.name }

        assertEquals(setOf("malformed", "empty-skill"), skills.keys)
        assertNull(skills.getValue("malformed").description)
        assertNull(skills.getValue("empty-skill").description)
    }

    @Test
    fun `should parse quoted and block frontmatter values`() {
        writeSkill(
            temporaryDirectory.resolve(".claude/skills/folder-name"),
            """
            ---
            name: "review-skill"
            description: >
              Reviews Kotlin
              and Java changes.
            ---
            Body
            """.trimIndent(),
        )

        val skill = ClaudeSkillProvider(temporaryDirectory).discoverGlobal().single()

        assertEquals("review-skill", skill.name)
        assertEquals("Reviews Kotlin and Java changes.", skill.description)
    }

    @Test
    fun `should ignore a large asset directory while fingerprinting SKILL md`() {
        val skillDirectory = temporaryDirectory.resolve(".agents/skills/asset-heavy")
        writeSkill(skillDirectory, "---\nname: asset-heavy\n---\nInstructions")
        val assets = Files.createDirectories(skillDirectory.resolve("assets"))
        Files.write(assets.resolve("large.bin"), ByteArray(2 * 1024 * 1024) { 7 })

        val skill = SharedSkillProvider(temporaryDirectory).discoverGlobal().single()

        assertEquals("asset-heavy", skill.name)
        assertTrue(skill.fingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `should tag project-scoped skills with the project name and use the SKILL md heading as display title`() {
        writeSkill(
            temporaryDirectory.resolve(".agents/skills/global-review"),
            """
            ---
            name: global-review
            ---
            # Global Review Skill

            Instructions
            """.trimIndent(),
        )
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"))
        writeSkill(
            projectRoot.resolve(".agents/skills/project-review"),
            """
            ---
            name: project-review
            ---
            # Project Review Skill

            Instructions
            """.trimIndent(),
        )

        val provider = SharedSkillProvider(temporaryDirectory)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(projectRoot)).single()

        assertNull(global.projectName)
        assertEquals("Global Review Skill", global.displayTitle)
        assertEquals("project", project.projectName)
        assertEquals("Project Review Skill", project.displayTitle)
    }

    @Test
    fun `specialized providers tag every project skill with the project name`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("named-project"))
        writeSkill(projectRoot.resolve(".agent/skills/antigravity-review"), "---\nname: antigravity-review\n---")
        writeSkill(projectRoot.resolve(".cline/skills/cline-review"), "---\nname: cline-review\n---")
        writeSkill(projectRoot.resolve(".github/skills/copilot-review"), "---\nname: copilot-review\n---")
        writeSkill(
            projectRoot.resolve(".opencode/skills/opencode-review"),
            validSkill("opencode-review", "Reviews OpenCode changes"),
        )
        writeSkill(projectRoot.resolve(".grok/skills/grok-review"), "---\nname: grok-review\n---")
        val project = project(projectRoot).copy(name = "Named Project")
        val providers = listOf(
            AntigravitySkillProvider(temporaryDirectory),
            ClineSkillProvider(temporaryDirectory),
            CopilotSkillProvider(temporaryDirectory.resolve(".copilot")),
            GrokSkillProvider(temporaryDirectory),
            OpenCodeSkillProvider(temporaryDirectory),
        )

        providers.forEach { provider ->
            val skills = provider.discoverProject(project)
            assertTrue(skills.isNotEmpty(), "${provider.agentId} should discover a project skill")
            assertTrue(
                skills.all { it.projectName == "Named Project" },
                "${provider.agentId} should preserve the project name",
            )
        }
    }

    @Test
    fun `should fall back to the frontmatter name when SKILL md has no heading`() {
        writeSkill(temporaryDirectory.resolve(".agents/skills/no-heading"), "---\nname: no-heading\n---\nBody only")

        val skill = SharedSkillProvider(temporaryDirectory).discoverGlobal().single()

        assertNull(skill.displayTitle)
    }

    @Test
    fun `should return no project skills when the project path is unavailable`() {
        val project = DiscoveredProject(
            identity = ProjectIdentity("missing", null, null, null),
            name = "missing",
            path = null,
            gitRoot = null,
            gitRemote = null,
            currentBranch = null,
            agents = emptyList(),
            lastActivity = null,
        )

        assertTrue(SharedSkillProvider(temporaryDirectory).discoverProject(project).isEmpty())
    }

    private fun writeSkill(skillDirectory: Path, content: String) {
        Files.createDirectories(skillDirectory)
        Files.writeString(skillDirectory.resolve("SKILL.md"), content)
    }

    private fun validSkill(name: String, description: String): String =
        "---\nname: $name\ndescription: $description\n---"

    private fun project(root: Path): DiscoveredProject = DiscoveredProject(
        identity = ProjectIdentity("project", root.toString(), root.toString(), null),
        name = "project",
        path = root.toString(),
        gitRoot = root.toString(),
        gitRemote = null,
        currentBranch = null,
        agents = emptyList(),
        lastActivity = null,
    )
}
