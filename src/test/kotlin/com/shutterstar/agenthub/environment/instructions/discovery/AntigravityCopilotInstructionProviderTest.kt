package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AntigravityCopilotInstructionProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should discover Antigravity global context workspace context and rules`() {
        val geminiHome = Files.createDirectories(temporaryDirectory.resolve(".gemini"))
        Files.writeString(geminiHome.resolve("GEMINI.md"), "Global Antigravity instructions")
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("antigravity-project"))
        Files.writeString(projectRoot.resolve("AGENTS.md"), "Agent instructions")
        Files.writeString(projectRoot.resolve("GEMINI.md"), "Gemini instructions")
        val rules = Files.createDirectories(projectRoot.resolve(".agents/rules/nested"))
        Files.writeString(rules.resolve("kotlin.md"), "Kotlin rule")
        val legacyRules = Files.createDirectories(projectRoot.resolve(".agent/rules"))
        Files.writeString(legacyRules.resolve("legacy.md"), "Legacy rule")

        val provider = AntigravityInstructionProvider(temporaryDirectory)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(projectRoot))

        assertEquals(InstructionScope.GLOBAL, global.scope)
        assertEquals(InstructionType.GEMINI_MD, global.type)
        assertEquals(4, project.size)
        assertEquals(
            setOf(InstructionType.AGENTS_MD, InstructionType.GEMINI_MD, InstructionType.ANTIGRAVITY_RULE),
            project.mapTo(mutableSetOf()) { it.type },
        )
        assertTrue(project.all { it.agentIds == setOf("antigravity") })
    }

    @Test
    fun `should discover Antigravity global agents md, global rules, and project alt and plugin rules`() {
        val geminiHome = Files.createDirectories(temporaryDirectory.resolve(".gemini"))
        Files.writeString(geminiHome.resolve("AGENTS.md"), "Global Antigravity AGENTS")
        val globalRules = Files.createDirectories(geminiHome.resolve("rules"))
        Files.writeString(globalRules.resolve("security.md"), "Security rule")

        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("antigravity-expanded-project"))
        val altRules = Files.createDirectories(projectRoot.resolve("_agents/rules"))
        Files.writeString(altRules.resolve("alt.md"), "Alt rule")
        val geminiRules = Files.createDirectories(projectRoot.resolve(".gemini/rules"))
        Files.writeString(geminiRules.resolve("gemini-rule.md"), "Gemini rule")
        val pluginRules = Files.createDirectories(projectRoot.resolve(".agents/plugins/my-plugin/rules"))
        Files.writeString(pluginRules.resolve("AGENTS.md"), "Plugin rule")

        val provider = AntigravityInstructionProvider(temporaryDirectory)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(projectRoot))

        assertEquals(2, global.size)
        assertEquals(
            setOf(InstructionType.AGENTS_MD, InstructionType.ANTIGRAVITY_RULE),
            global.mapTo(mutableSetOf()) { it.type },
        )
        assertEquals(3, project.size)
        assertTrue(project.all { it.type == InstructionType.ANTIGRAVITY_RULE && it.agentIds == setOf("antigravity") })
    }

    @Test
    fun `should discover Copilot personal repository and compatible agent instructions`() {
        val copilotHome = Files.createDirectories(temporaryDirectory.resolve(".copilot"))
        Files.writeString(copilotHome.resolve("copilot-instructions.md"), "Personal instructions")
        val globalInstructions = Files.createDirectories(copilotHome.resolve("instructions/nested"))
        Files.writeString(globalInstructions.resolve("kotlin.instructions.md"), "Global Kotlin instructions")
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("copilot-project"))
        Files.writeString(projectRoot.resolve("AGENTS.md"), "Agent instructions")
        Files.writeString(projectRoot.resolve("CLAUDE.md"), "Claude-compatible instructions")
        Files.writeString(projectRoot.resolve("GEMINI.md"), "Gemini-compatible instructions")
        val github = Files.createDirectories(projectRoot.resolve(".github"))
        Files.writeString(github.resolve("copilot-instructions.md"), "Repository instructions")
        val modular = Files.createDirectories(github.resolve("instructions/backend"))
        Files.writeString(modular.resolve("api.instructions.md"), "API instructions")

        val provider = CopilotInstructionProvider(copilotHome)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(projectRoot))

        assertEquals(2, global.size)
        assertTrue(global.all { it.scope == InstructionScope.GLOBAL && it.agentIds == setOf("copilot") })
        assertEquals(5, project.size)
        assertEquals(
            setOf(
                InstructionType.AGENTS_MD,
                InstructionType.CLAUDE_MD,
                InstructionType.GEMINI_MD,
                InstructionType.COPILOT_INSTRUCTION,
            ),
            project.mapTo(mutableSetOf()) { it.type },
        )
    }

    @Test
    fun `should merge shared Antigravity and Copilot context files for comparison`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("shared-project"))
        Files.writeString(projectRoot.resolve("AGENTS.md"), "Shared agent instructions")
        Files.writeString(projectRoot.resolve("GEMINI.md"), "Shared Gemini instructions")
        val service = InstructionDiscoveryService(
            listOf(
                AntigravityInstructionProvider(temporaryDirectory),
                CopilotInstructionProvider(temporaryDirectory.resolve(".copilot")),
            ),
        )

        val instructions = service.discoverProject(project(projectRoot)).associateBy { it.type }

        assertEquals(setOf("antigravity", "copilot"), instructions.getValue(InstructionType.AGENTS_MD).agentIds)
        assertEquals(setOf("antigravity", "copilot"), instructions.getValue(InstructionType.GEMINI_MD).agentIds)
    }

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
