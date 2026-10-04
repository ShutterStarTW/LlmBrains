package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.writeSkillMd
import com.shutterstar.agenthub.project
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        writeSkillMd(
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
        writeSkillMd(
            projectRoot.resolve(".agents/skills/project-review"),
            """
            ---
            name: project-review
            description: Reviews this project
            ---
            Instructions
            """.trimIndent(),
        )
        writeSkillMd(
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
        writeSkillMd(temporaryDirectory.resolve(".claude/skills/claude-review"), "---\nname: claude-review\n---")
        writeSkillMd(temporaryDirectory.resolve(".codex/skills/codex-review"), "---\nname: codex-review\n---")

        val claude = ClaudeSkillProvider(temporaryDirectory).discoverGlobal().single()
        val codex = CodexSkillProvider(temporaryDirectory).discoverGlobal().single()

        assertEquals("claude", claude.agentId)
        assertEquals("claude-review", claude.name)
        assertEquals("codex", codex.agentId)
        assertEquals("codex-review", codex.name)
    }

    @Test
    fun `should flag Claude's synced skills as system, distinct from and not duplicating the plain top-level ones`() {
        writeSkillMd(temporaryDirectory.resolve(".claude/skills/own-skill"), validSkill("own-skill", "The user's own skill"))
        // Claude Code caches skills synced from claude.ai under skills/synced/<bucket>/ - nested one
        // level deeper than a plain skill directory.
        writeSkillMd(
            temporaryDirectory.resolve(".claude/skills/synced/bucket-1/pdf"),
            validSkill("pdf", "Anthropic's built-in PDF skill"),
        )

        val skills = ClaudeSkillProvider(temporaryDirectory).discoverGlobal()

        assertEquals(setOf("own-skill", "pdf"), skills.mapTo(mutableSetOf()) { it.name })
        assertFalse(skills.single { it.name == "own-skill" }.system)
        assertTrue(skills.single { it.name == "pdf" }.system)
        assertTrue(skills.all { it.agentId == "claude" && it.scope == SkillScope.GLOBAL })
    }

    @Test
    fun `should discover Codex's dot-prefixed system skills, invisible to the normal dot-directory skip`() {
        writeSkillMd(temporaryDirectory.resolve(".codex/skills/own-skill"), validSkill("own-skill", "The user's own skill"))
        writeSkillMd(
            temporaryDirectory.resolve(".codex/skills/.system/skill-creator"),
            validSkill("skill-creator", "Codex's built-in skill-creator"),
        )

        val skills = CodexSkillProvider(temporaryDirectory).discoverGlobal()

        assertEquals(setOf("own-skill", "skill-creator"), skills.mapTo(mutableSetOf()) { it.name })
        assertFalse(skills.single { it.name == "own-skill" }.system)
        assertTrue(skills.single { it.name == "skill-creator" }.system)
    }

    @Test
    fun `should discover nested Cursor and OpenCode skill locations`() {
        writeSkillMd(
            temporaryDirectory.resolve(".cursor/skills/team/cursor-review"),
            "---\nname: cursor-review\ndescription: Reviews Cursor changes\n---",
        )
        writeSkillMd(
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
        writeSkillMd(
            temporaryDirectory.resolve(".claude/skills/global-compat"),
            validSkill("global-compat", "Global OpenCode-compatible skill"),
        )
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("opencode-project"))
        writeSkillMd(
            projectRoot.resolve("packages/app/.opencode/skills/native-project"),
            validSkill("native-project", "Nested native OpenCode skill"),
        )
        writeSkillMd(
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
        writeSkillMd(
            temporaryDirectory.resolve(".cursor/skills/native-review"),
            validSkill("native-review", "Reviews native Cursor changes"),
        )
        writeSkillMd(
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
        writeSkillMd(
            temporaryDirectory.resolve(".claude/skills/claude-review"),
            validSkill("claude-review", "Cursor-compatible Claude skill"),
        )
        writeSkillMd(
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
        // "skills-cursor" is Cursor's own vendor-managed pack (has a .sync-manifest.json Cursor
        // itself writes) - flagged system, unlike everything the user placed in the other roots.
        assertTrue(skills.single { it.name == "managed-review" }.system)
        assertTrue(skills.filterNot { it.name == "managed-review" }.none { it.system })
    }

    @Test
    fun `should discover nested and compatibility Cursor project skills`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("cursor-project"))
        writeSkillMd(
            projectRoot.resolve(".cursor/skills/root-review"),
            validSkill("root-review", "Reviews the repository"),
        )
        writeSkillMd(
            projectRoot.resolve("apps/web/.cursor/skills/web-review"),
            validSkill("web-review", "Reviews the web package"),
        )
        writeSkillMd(
            projectRoot.resolve(".claude/skills/claude-review"),
            validSkill("claude-review", "Cursor-compatible Claude skill"),
        )
        writeSkillMd(
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
        writeSkillMd(
            temporaryDirectory.resolve(".cursor/skills/missing-description"),
            "---\nname: missing-description\n---",
        )
        writeSkillMd(
            temporaryDirectory.resolve(".cursor/skills/folder-name"),
            validSkill("different-name", "Name does not match its directory"),
        )
        writeSkillMd(
            temporaryDirectory.resolve(".cursor/skills/Valid_Name"),
            validSkill("Valid_Name", "Name does not use the supported format"),
        )
        writeSkillMd(
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
        writeSkillMd(target, validSkill("linked-skill", "Linked Cursor skill"))
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
        writeSkillMd(
            agentPlugin.resolve("skills/agent-skill"),
            validSkill("agent-skill", "Agent plugin skill"),
        )

        val customPlugin = Files.createDirectories(temporaryDirectory.resolve(".cursor/plugins/local/custom-plugin"))
        Files.createDirectories(customPlugin.resolve(".cursor-plugin"))
        Files.writeString(
            customPlugin.resolve(".cursor-plugin/plugin.json"),
            """{"name":"custom-plugin","skills":"custom-skills"}""",
        )
        writeSkillMd(
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
        writeSkillMd(projectRoot.resolve(".cursor/skills/first"), validSkill("first", "First skill"))
        writeSkillMd(
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
        writeSkillMd(
            temporaryDirectory.resolve(".gemini/config/skills/global-review"),
            "---\nname: global-review\n---",
        )
        writeSkillMd(
            temporaryDirectory.resolve(".gemini/antigravity-cli/skills/cli-review"),
            "---\nname: cli-review\n---",
        )
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("antigravity-project"))
        writeSkillMd(projectRoot.resolve(".agent/skills/legacy-review"), "---\nname: legacy-review\n---")

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
        writeSkillMd(
            temporaryDirectory.resolve(".gemini/skills/direct-user-skill"),
            "---\nname: direct-user-skill\n---",
        )
        writeSkillMd(
            temporaryDirectory.resolve(".gemini/antigravity-cli/builtin/skills/builtin-skill"),
            "---\nname: builtin-skill\n---",
        )
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("antigravity-full-project"))
        writeSkillMd(projectRoot.resolve("_agents/skills/alt-agents-skill"), "---\nname: alt-agents-skill\n---")
        writeSkillMd(projectRoot.resolve(".gemini/skills/project-gemini-skill"), "---\nname: project-gemini-skill\n---")
        writeSkillMd(
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
        // Antigravity's own vendor-shipped pack lives under builtin/skills - flagged system, unlike
        // the direct root above.
        assertTrue(global.single { it.name == "builtin-skill" }.system)
        assertFalse(global.single { it.name == "direct-user-skill" }.system)
        assertEquals(
            setOf("alt-agents-skill", "project-gemini-skill", "plugin-skill"),
            project.mapTo(mutableSetOf()) { it.name },
        )
        assertTrue(project.all { it.agentId == "antigravity" && it.scope == SkillScope.PROJECT })
    }

    @Test
    fun `should discover Copilot personal and compatible project skills`() {
        val copilotHome = temporaryDirectory.resolve(".copilot")
        writeSkillMd(copilotHome.resolve("skills/personal-review"), "---\nname: personal-review\n---")
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("copilot-project"))
        writeSkillMd(projectRoot.resolve(".github/skills/github-review"), "---\nname: github-review\n---")
        writeSkillMd(projectRoot.resolve(".claude/skills/claude-review"), "---\nname: claude-review\n---")

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
        writeSkillMd(
            temporaryDirectory.resolve(".claude/skills/malformed"),
            "---\nname: never-closed\ndescription: malformed",
        )
        writeSkillMd(temporaryDirectory.resolve(".claude/skills/empty-skill"), "")

        val skills = ClaudeSkillProvider(temporaryDirectory).discoverGlobal().associateBy { it.name }

        assertEquals(setOf("malformed", "empty-skill"), skills.keys)
        assertNull(skills.getValue("malformed").description)
        assertNull(skills.getValue("empty-skill").description)
    }

    @Test
    fun `should parse quoted and block frontmatter values`() {
        writeSkillMd(
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
        writeSkillMd(skillDirectory, "---\nname: asset-heavy\n---\nInstructions")
        val assets = Files.createDirectories(skillDirectory.resolve("assets"))
        Files.write(assets.resolve("large.bin"), ByteArray(2 * 1024 * 1024) { 7 })

        val skill = SharedSkillProvider(temporaryDirectory).discoverGlobal().single()

        assertEquals("asset-heavy", skill.name)
        assertTrue(skill.fingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `should tag project-scoped skills with the project name and use the SKILL md heading as display title`() {
        writeSkillMd(
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
        writeSkillMd(
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
        writeSkillMd(projectRoot.resolve(".agent/skills/antigravity-review"), "---\nname: antigravity-review\n---")
        writeSkillMd(projectRoot.resolve(".cline/skills/cline-review"), "---\nname: cline-review\n---")
        writeSkillMd(projectRoot.resolve(".github/skills/copilot-review"), "---\nname: copilot-review\n---")
        writeSkillMd(
            projectRoot.resolve(".opencode/skills/opencode-review"),
            validSkill("opencode-review", "Reviews OpenCode changes"),
        )
        writeSkillMd(projectRoot.resolve(".grok/skills/grok-review"), "---\nname: grok-review\n---")
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
        writeSkillMd(temporaryDirectory.resolve(".agents/skills/no-heading"), "---\nname: no-heading\n---\nBody only")

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

    private fun validSkill(name: String, description: String): String =
        "---\nname: $name\ndescription: $description\n---"
}
