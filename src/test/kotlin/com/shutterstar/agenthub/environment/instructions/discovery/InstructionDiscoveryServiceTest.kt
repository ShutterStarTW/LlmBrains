package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InstructionDiscoveryServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should merge AGENTS md support for Codex Cursor and OpenCode`() {
        val projectRoot = projectRoot()
        Files.writeString(projectRoot.resolve("AGENTS.md"), "Repository instructions")

        val source = service().discoverProject(project(projectRoot)).single()

        assertEquals(InstructionType.AGENTS_MD, source.type)
        assertEquals(InstructionScope.PROJECT, source.scope)
        assertEquals(setOf("codex", "cursor", "opencode"), source.agentIds)
    }

    @Test
    fun `should merge project root CLAUDE md support for Claude Cursor and OpenCode`() {
        val projectRoot = projectRoot()
        Files.writeString(projectRoot.resolve("CLAUDE.md"), "Claude instructions")

        val source = service().discoverProject(project(projectRoot)).single()

        assertEquals(InstructionType.CLAUDE_MD, source.type)
        assertEquals(setOf("claude", "cursor", "opencode"), source.agentIds)
    }

    @Test
    fun `should discover both AGENTS and Claude instructions`() {
        val projectRoot = projectRoot()
        Files.writeString(projectRoot.resolve("AGENTS.md"), "Shared instructions")
        Files.writeString(projectRoot.resolve("CLAUDE.md"), "Claude instructions")

        val sources = service().discoverProject(project(projectRoot))

        assertEquals(2, sources.size)
        assertEquals(setOf(InstructionType.AGENTS_MD, InstructionType.CLAUDE_MD), sources.mapTo(mutableSetOf()) { it.type })
    }

    @Test
    fun `should discover only MDC files from Cursor rules recursively`() {
        val projectRoot = projectRoot()
        val rules = Files.createDirectories(projectRoot.resolve(".cursor/rules/frontend"))
        Files.writeString(rules.resolve("components.mdc"), "---\nalwaysApply: true\n---\nUse components")
        Files.writeString(rules.resolve("ignored.md"), "Wrong extension")

        val source = CursorInstructionProvider().discoverProject(project(projectRoot)).single()

        assertEquals(InstructionType.CURSOR_RULE, source.type)
        assertTrue(source.path.endsWith("components.mdc"))
    }

    @Test
    fun `should ignore nested CLAUDE md and ancestor cursor rules segments`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve(".cursor/rules/project"))
        Files.writeString(projectRoot.resolve("CLAUDE.md"), "Root compatibility instructions")
        val nested = Files.createDirectories(projectRoot.resolve("nested"))
        Files.writeString(nested.resolve("CLAUDE.md"), "Not a Cursor instruction")
        Files.writeString(nested.resolve("notes.mdc"), "Not inside this project's .cursor/rules")

        val sources = CursorInstructionProvider(temporaryDirectory).discoverProject(project(projectRoot))

        assertEquals(1, sources.size)
        assertEquals(InstructionType.CLAUDE_MD, sources.single().type)
    }

    @Test
    fun `should discover default and custom local plugin rules`() {
        val defaultPlugin = Files.createDirectories(temporaryDirectory.resolve(".cursor/plugins/local/default-plugin"))
        Files.createDirectories(defaultPlugin.resolve(".cursor-plugin"))
        Files.writeString(defaultPlugin.resolve(".cursor-plugin/plugin.json"), """{"name":"default-plugin"}""")
        val defaultRules = Files.createDirectories(defaultPlugin.resolve("rules"))
        Files.writeString(defaultRules.resolve("default.mdc"), "Default plugin rule")

        val customPlugin = Files.createDirectories(temporaryDirectory.resolve(".cursor/plugins/local/custom-plugin"))
        Files.createDirectories(customPlugin.resolve(".cursor-plugin"))
        Files.writeString(
            customPlugin.resolve(".cursor-plugin/plugin.json"),
            """{"name":"custom-plugin","rules":"guidance/custom.markdown"}""",
        )
        val guidance = Files.createDirectories(customPlugin.resolve("guidance"))
        Files.writeString(guidance.resolve("custom.markdown"), "Custom plugin rule")

        val sources = CursorInstructionProvider(temporaryDirectory).discoverGlobal()

        assertEquals(2, sources.size)
        assertTrue(sources.all { it.type == InstructionType.CURSOR_RULE && it.scope == InstructionScope.GLOBAL })
    }

    @Test
    fun `should prefer Codex override files and ignore empty instructions`() {
        val projectRoot = projectRoot()
        Files.writeString(projectRoot.resolve("AGENTS.md"), "Base")
        Files.writeString(projectRoot.resolve("AGENTS.override.md"), "Override")
        val nested = Files.createDirectories(projectRoot.resolve("module"))
        Files.writeString(nested.resolve("AGENTS.md"), "")

        val sources = CodexInstructionProvider(temporaryDirectory.resolve(".codex"))
            .discoverProject(project(projectRoot))

        assertEquals(1, sources.size)
        assertTrue(sources.single().path.endsWith("AGENTS.override.md"))
    }

    @Test
    fun `should discover global Codex and Claude instructions`() {
        val codexHome = Files.createDirectories(temporaryDirectory.resolve(".codex"))
        val claudeHome = Files.createDirectories(temporaryDirectory.resolve(".claude"))
        val openCodeHome = Files.createDirectories(temporaryDirectory.resolve(".config/opencode"))
        Files.writeString(codexHome.resolve("AGENTS.md"), "Codex global")
        Files.writeString(codexHome.resolve("AGENTS.override.md"), "Codex override")
        Files.writeString(claudeHome.resolve("CLAUDE.md"), "Claude global")
        Files.writeString(openCodeHome.resolve("AGENTS.md"), "OpenCode global")
        val rules = Files.createDirectories(claudeHome.resolve("rules"))
        Files.writeString(rules.resolve("testing.md"), "Testing rule")

        val sources = service().discoverGlobal()

        assertEquals(4, sources.size)
        assertTrue(sources.any { it.path.endsWith("AGENTS.override.md") })
        assertTrue(sources.none { it.path.endsWith(".codex${java.io.File.separator}AGENTS.md") })
        assertTrue(sources.all { it.scope == InstructionScope.GLOBAL })
    }

    @Test
    fun `should use Claude instructions as an OpenCode fallback`() {
        val claudeHome = Files.createDirectories(temporaryDirectory.resolve(".claude"))
        Files.writeString(claudeHome.resolve("CLAUDE.md"), "Global fallback")
        val projectRoot = projectRoot()
        Files.writeString(projectRoot.resolve("CLAUDE.md"), "Project fallback")

        val provider = OpenCodeInstructionProvider(temporaryDirectory)

        assertEquals(InstructionType.CLAUDE_MD, provider.discoverGlobal().single().type)
        assertEquals(InstructionType.CLAUDE_MD, provider.discoverProject(project(projectRoot)).single().type)

        Files.writeString(projectRoot.resolve("AGENTS.md"), "Preferred project instructions")
        val projectSources = provider.discoverProject(project(projectRoot))
        assertEquals(1, projectSources.size)
        assertEquals(InstructionType.AGENTS_MD, projectSources.single().type)
    }

    @Test
    fun `should ignore excluded dependency directories`() {
        val projectRoot = projectRoot()
        val dependency = Files.createDirectories(projectRoot.resolve("node_modules/package"))
        Files.writeString(dependency.resolve("AGENTS.md"), "Ignore this")

        assertTrue(service().discoverProject(project(projectRoot)).isEmpty())
    }

    @Test
    fun `should return no sources for missing files or unavailable project path`() {
        val projectRoot = projectRoot()
        assertTrue(service().discoverProject(project(projectRoot)).isEmpty())

        val unavailable = DiscoveredProject(
            identity = ProjectIdentity("missing", null, null, null),
            name = "missing",
            path = null,
            gitRoot = null,
            gitRemote = null,
            currentBranch = null,
            agents = emptyList(),
            lastActivity = null,
        )
        assertTrue(service().discoverProject(unavailable).isEmpty())
    }

    @Test
    fun `should preserve healthy provider results when another provider fails`() {
        val failingProvider = object : InstructionProvider {
            override val agentId: String = "broken"

            override fun discoverGlobal(): List<InstructionSource> = error("fixture failure")

            override fun discoverProject(project: DiscoveredProject) = emptyList<InstructionSource>()
        }
        val healthyProvider = object : InstructionProvider {
            override val agentId: String = "codex"

            override fun discoverGlobal(): List<InstructionSource> = listOf(
                InstructionSource(
                    path = "/codex/AGENTS.md",
                    scope = InstructionScope.GLOBAL,
                    agentIds = setOf(agentId),
                    type = InstructionType.AGENTS_MD,
                ),
            )

            override fun discoverProject(project: DiscoveredProject) = emptyList<InstructionSource>()
        }

        val sources = InstructionDiscoveryService(listOf(failingProvider, healthyProvider)).discoverGlobal()

        assertEquals(listOf("/codex/AGENTS.md"), sources.map { it.path })
    }

    private fun service(): InstructionDiscoveryService = InstructionDiscoveryService(
        providers = listOf(
            CodexInstructionProvider(temporaryDirectory.resolve(".codex")),
            ClaudeInstructionProvider(temporaryDirectory),
            CursorInstructionProvider(temporaryDirectory),
            OpenCodeInstructionProvider(temporaryDirectory),
        ),
    )

    private fun projectRoot(): Path = Files.createDirectories(temporaryDirectory.resolve("project"))

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
