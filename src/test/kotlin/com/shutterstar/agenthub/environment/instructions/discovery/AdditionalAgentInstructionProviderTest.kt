package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.writeFile
import com.shutterstar.agenthub.project
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AdditionalAgentInstructionProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should attribute global Claude rules to Grok compatibility`() {
        val claudeRules = Files.createDirectories(temporaryDirectory.resolve(".claude/rules"))
        Files.writeString(temporaryDirectory.resolve(".claude/CLAUDE.md"), "Global guidance")
        Files.writeString(claudeRules.resolve("security.md"), "Security guidance")

        val sources = GrokInstructionProvider(temporaryDirectory).discoverGlobal()

        assertEquals(2, sources.size)
        assertTrue(sources.all { it.agentIds == setOf("grok") })
    }

    @Test
    fun `should discover Cline global and project rules`() {
        writeFile(temporaryDirectory.resolve(".cline/rules/global.md"), "Instructions")
        writeFile(temporaryDirectory.resolve("Documents/Cline/Rules/compatibility.txt"), "Instructions")
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("cline-project"))
        writeFile(projectRoot.resolve("AGENTS.md"), "Instructions")
        writeFile(projectRoot.resolve(".cline/rules/native.md"), "Instructions")
        writeFile(projectRoot.resolve(".clinerules/legacy.txt"), "Instructions")
        writeFile(projectRoot.resolve("node_modules/package/.clinerules/ignored.md"), "Instructions")

        val provider = ClineInstructionProvider(temporaryDirectory)
        val project = provider.discoverProject(project(projectRoot))

        assertEquals(2, provider.discoverGlobal().size)
        assertEquals(3, project.size)
        assertTrue(project.all { it.agentIds == setOf("cline") && it.scope == InstructionScope.PROJECT })
    }

    @Test
    fun `should discover Kiro steering and nested AGENTS md files`() {
        writeFile(temporaryDirectory.resolve(".kiro/steering/global.md"), "Instructions")
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("kiro-project"))
        writeFile(projectRoot.resolve(".kiro/steering/product.md"), "Instructions")
        writeFile(projectRoot.resolve("src/AGENTS.md"), "Instructions")

        val provider = KiroInstructionProvider(temporaryDirectory)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(projectRoot))

        assertEquals(InstructionScope.GLOBAL, global.scope)
        assertEquals(2, project.size)
        assertEquals(setOf(InstructionType.AGENT_SPECIFIC, InstructionType.AGENTS_MD), project.mapTo(mutableSetOf()) { it.type })
    }

    @Test
    fun `should discover Qwen global project local and AGENTS md instructions`() {
        writeFile(temporaryDirectory.resolve(".qwen/QWEN.md"), "Instructions")
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("qwen-project"))
        writeFile(projectRoot.resolve("QWEN.md"), "Instructions")
        writeFile(projectRoot.resolve(".qwen/QWEN.local.md"), "Instructions")
        writeFile(projectRoot.resolve("src/AGENTS.md"), "Instructions")
        writeFile(projectRoot.resolve("nested/QWEN.md"), "Instructions")

        val provider = QwenInstructionProvider(temporaryDirectory)
        val project = provider.discoverProject(project(projectRoot))

        assertEquals(1, provider.discoverGlobal().size)
        assertEquals(3, project.size)
        assertEquals(1, project.count { it.type == InstructionType.AGENTS_MD })
    }

    @Test
    fun `should discover Grok global rules nested skills-compatible instructions and project name`() {
        writeFile(temporaryDirectory.resolve(".grok/AGENTS.md"), "Instructions")
        writeFile(temporaryDirectory.resolve(".grok/rules/global.md"), "Instructions")
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("grok-project"))
        writeFile(projectRoot.resolve("AGENTS.md"), "Instructions")
        writeFile(projectRoot.resolve("AGENT.md"), "Instructions")
        writeFile(projectRoot.resolve("CLAUDE.md"), "Instructions")
        writeFile(projectRoot.resolve(".grok/rules/native.md"), "Instructions")
        writeFile(projectRoot.resolve(".claude/rules/compatible.md"), "Instructions")
        writeFile(projectRoot.resolve(".cursor/rules/style.mdc"), "Instructions")
        writeFile(projectRoot.resolve("node_modules/package/.grok/rules/ignored.md"), "Instructions")

        val provider = GrokInstructionProvider(temporaryDirectory)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(projectRoot))

        assertEquals(2, global.size)
        assertEquals(InstructionScope.GLOBAL, global.first().scope)
        assertEquals(6, project.size)
        assertTrue(project.all { it.agentIds == setOf("grok") && it.scope == InstructionScope.PROJECT })
        assertTrue(project.all { it.projectName == "project" })
        assertEquals(
            setOf(InstructionType.AGENTS_MD, InstructionType.CLAUDE_MD, InstructionType.AGENT_SPECIFIC, InstructionType.CURSOR_RULE),
            project.mapTo(mutableSetOf()) { it.type },
        )
        assertEquals(2, project.count { it.type == InstructionType.AGENTS_MD })
    }
}
