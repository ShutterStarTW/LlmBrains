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
    fun `should list the global vendor-neutral AGENTS md that Cline reads as a rule`() {
        writeFile(temporaryDirectory.resolve(".agents/AGENTS.md"), "Instructions")

        val global = ClineInstructionProvider(temporaryDirectory).discoverGlobal()

        assertEquals(1, global.size)
        assertEquals(InstructionType.AGENTS_MD, global.single().type)
        assertEquals(InstructionScope.GLOBAL, global.single().scope)
        assertTrue(global.single().path.replace(java.io.File.separatorChar, '/').endsWith("/.agents/AGENTS.md"))
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
    fun `should discover Qwen global project local rules and AGENTS md instructions`() {
        writeFile(temporaryDirectory.resolve(".qwen/QWEN.md"), "Instructions")
        writeFile(temporaryDirectory.resolve(".qwen/rules/team/style.md"), "Instructions")
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("qwen-project"))
        writeFile(projectRoot.resolve("QWEN.md"), "Instructions")
        writeFile(projectRoot.resolve(".qwen/QWEN.local.md"), "Instructions")
        writeFile(projectRoot.resolve("src/AGENTS.md"), "Instructions")
        writeFile(projectRoot.resolve("nested/QWEN.md"), "Instructions")
        writeFile(projectRoot.resolve(".qwen/rules/frontend/react.md"), "Instructions")
        writeFile(projectRoot.resolve(".qwen/rules/notes.txt"), "Not a rule")

        val provider = QwenInstructionProvider(temporaryDirectory)
        val project = provider.discoverProject(project(projectRoot))

        assertEquals(2, provider.discoverGlobal().size)
        assertEquals(5, project.size)
        // Qwen searches upward from the working directory, so only a nested QWEN.md carries a note.
        assertEquals(listOf("nested/QWEN.md"), project.filter { it.agentNotes.isNotEmpty() }.map { projectRoot.relativize(Path.of(it.path)).toString().replace('\\', '/') })
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

    @Test
    fun `should list Claude AGENTS dot md and flag it unused only where Claude ignores it`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("agents-only"))
        Files.writeString(projectRoot.resolve("AGENTS.md"), "Shared guidance")
        Files.writeString(Files.createDirectories(projectRoot.resolve("pkg")).resolve("AGENTS.md"), "Package guidance")
        Files.writeString(Files.createDirectories(projectRoot.resolve(".agents")).resolve("AGENTS.md"), "Not read")

        val sources = ClaudeInstructionProvider(temporaryDirectory).discoverProject(project(projectRoot))
        val byPath = sources.associateBy { projectRoot.relativize(Path.of(it.path)).toString().replace('\\', '/') }

        assertEquals(setOf("AGENTS.md", "pkg/AGENTS.md", ".agents/AGENTS.md"), byPath.keys)
        assertTrue(sources.all { it.type == InstructionType.AGENTS_MD && it.agentIds == setOf("claude") })
        assertTrue(byPath.getValue("AGENTS.md").agentNotes.isEmpty())
        assertTrue(byPath.getValue("pkg/AGENTS.md").agentNotes.isEmpty())
        assertTrue(byPath.getValue(".agents/AGENTS.md").agentNotes.getValue("claude").contains(".agents"))
    }

    @Test
    fun `should list Claude AGENTS dot md with a reason when CLAUDE md or CLAUDE local md exists at the project root`() {
        val withClaude = Files.createDirectories(temporaryDirectory.resolve("with-claude"))
        Files.writeString(withClaude.resolve("AGENTS.md"), "Shared guidance")
        Files.writeString(Files.createDirectories(withClaude.resolve(".claude")).resolve("CLAUDE.md"), "Claude guidance")
        val withLocal = Files.createDirectories(temporaryDirectory.resolve("with-local"))
        Files.writeString(withLocal.resolve("AGENTS.md"), "Shared guidance")
        Files.writeString(withLocal.resolve("CLAUDE.local.md"), "Personal guidance")

        val provider = ClaudeInstructionProvider(temporaryDirectory)

        listOf(withClaude, withLocal).forEach { root ->
            val sources = provider.discoverProject(project(root))
            assertEquals(setOf(InstructionType.AGENTS_MD, InstructionType.CLAUDE_MD), sources.map { it.type }.toSet())
            assertTrue(sources.first { it.type == InstructionType.AGENTS_MD }.agentNotes.getValue("claude").contains("CLAUDE.md"))
            assertTrue(sources.first { it.type == InstructionType.CLAUDE_MD }.agentNotes.isEmpty())
        }
    }

    @Test
    fun `should flag a nested Claude AGENTS dot md only when its own directory has a CLAUDE md`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("nested"))
        val covered = Files.createDirectories(projectRoot.resolve("covered"))
        Files.writeString(covered.resolve("AGENTS.md"), "Covered")
        Files.writeString(covered.resolve("CLAUDE.md"), "Claude")
        Files.writeString(Files.createDirectories(projectRoot.resolve("plain")).resolve("AGENTS.md"), "Plain")

        val sources = ClaudeInstructionProvider(temporaryDirectory).discoverProject(project(projectRoot))
        val unused = sources.filter { it.agentNotes.isNotEmpty() }.map { projectRoot.relativize(Path.of(it.path)).toString().replace('\\', '/') }

        assertEquals(listOf("covered/AGENTS.md"), unused)
        assertEquals(3, sources.size)
    }
}
