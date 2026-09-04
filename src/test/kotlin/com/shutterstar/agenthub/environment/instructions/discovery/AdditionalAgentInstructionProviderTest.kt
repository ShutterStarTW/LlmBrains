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

class AdditionalAgentInstructionProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should discover Cline global and project rules`() {
        write(temporaryDirectory.resolve(".cline/rules/global.md"))
        write(temporaryDirectory.resolve("Documents/Cline/Rules/compatibility.txt"))
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("cline-project"))
        write(projectRoot.resolve("AGENTS.md"))
        write(projectRoot.resolve(".cline/rules/native.md"))
        write(projectRoot.resolve(".clinerules/legacy.txt"))
        write(projectRoot.resolve("node_modules/package/.clinerules/ignored.md"))

        val provider = ClineInstructionProvider(temporaryDirectory)
        val project = provider.discoverProject(project(projectRoot))

        assertEquals(2, provider.discoverGlobal().size)
        assertEquals(3, project.size)
        assertTrue(project.all { it.agentIds == setOf("cline") && it.scope == InstructionScope.PROJECT })
    }

    @Test
    fun `should discover Kiro steering and nested AGENTS md files`() {
        write(temporaryDirectory.resolve(".kiro/steering/global.md"))
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("kiro-project"))
        write(projectRoot.resolve(".kiro/steering/product.md"))
        write(projectRoot.resolve("src/AGENTS.md"))

        val provider = KiroInstructionProvider(temporaryDirectory)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(projectRoot))

        assertEquals(InstructionScope.GLOBAL, global.scope)
        assertEquals(2, project.size)
        assertEquals(setOf(InstructionType.AGENT_SPECIFIC, InstructionType.AGENTS_MD), project.mapTo(mutableSetOf()) { it.type })
    }

    @Test
    fun `should discover Qwen global project local and AGENTS md instructions`() {
        write(temporaryDirectory.resolve(".qwen/QWEN.md"))
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("qwen-project"))
        write(projectRoot.resolve("QWEN.md"))
        write(projectRoot.resolve(".qwen/QWEN.local.md"))
        write(projectRoot.resolve("src/AGENTS.md"))
        write(projectRoot.resolve("nested/QWEN.md"))

        val provider = QwenInstructionProvider(temporaryDirectory)
        val project = provider.discoverProject(project(projectRoot))

        assertEquals(1, provider.discoverGlobal().size)
        assertEquals(3, project.size)
        assertEquals(1, project.count { it.type == InstructionType.AGENTS_MD })
    }

    @Test
    fun `should discover Grok global rules nested skills-compatible instructions and project name`() {
        write(temporaryDirectory.resolve(".grok/AGENTS.md"))
        write(temporaryDirectory.resolve(".grok/rules/global.md"))
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("grok-project"))
        write(projectRoot.resolve("AGENTS.md"))
        write(projectRoot.resolve("AGENT.md"))
        write(projectRoot.resolve("CLAUDE.md"))
        write(projectRoot.resolve(".grok/rules/native.md"))
        write(projectRoot.resolve(".claude/rules/compatible.md"))
        write(projectRoot.resolve(".cursor/rules/style.mdc"))
        write(projectRoot.resolve("node_modules/package/.grok/rules/ignored.md"))

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

    private fun write(path: Path) {
        Files.createDirectories(path.parent)
        Files.writeString(path, "Instructions")
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
