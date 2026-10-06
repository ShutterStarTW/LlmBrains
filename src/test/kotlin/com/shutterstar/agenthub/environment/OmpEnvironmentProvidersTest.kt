package com.shutterstar.agenthub.environment

import com.shutterstar.agenthub.environment.config.discovery.OmpConfigProvider
import com.shutterstar.agenthub.environment.instructions.discovery.OmpInstructionProvider
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.environment.mcp.discovery.OmpMcpProvider
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.environment.skills.discovery.OmpSkillProvider
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.project
import com.shutterstar.agenthub.writeFile
import com.shutterstar.agenthub.writeSkill
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OmpEnvironmentProvidersTest {
    @TempDir
    lateinit var home: Path

    private fun relative(root: Path, file: String) = root.relativize(Path.of(file)).toString().replace('\\', '/')

    @Test
    fun `should discover global skills in the native and managed directories only`() {
        writeSkill(home.resolve(".omp/agent/skills/native"), "native")
        writeSkill(home.resolve(".omp/agent/managed-skills/learned"), "learned")
        writeSkill(home.resolve(".claude/skills/claude-user"), "claude-user")

        val records = OmpSkillProvider(home).discoverGlobal()

        assertEquals(setOf("native", "learned"), records.map { it.name }.toSet())
        assertTrue(records.all { it.agentId == "omp" && it.scope == SkillScope.GLOBAL && !it.shared })
    }

    @Test
    fun `should discover project skills in omp claude and codex roots with the project name`() {
        val root = Files.createDirectories(home.resolve("project"))
        writeSkill(root.resolve(".omp/skills/own"), "own")
        writeSkill(root.resolve("packages/app/.omp/skills/nested"), "nested")
        writeSkill(root.resolve(".claude/skills/claude"), "claude")
        writeSkill(root.resolve(".codex/skills/codex"), "codex")
        writeSkill(root.resolve(".opencode/skills/other"), "other")

        val records = OmpSkillProvider(home).discoverProject(project(root))

        assertEquals(setOf("own", "nested", "claude", "codex"), records.map { it.name }.toSet())
        assertTrue(records.all { it.scope == SkillScope.PROJECT && it.projectName == "project" })
    }

    @Test
    fun `should read mcpServers from the agent directory and the project omp and root files`() {
        writeFile(home.resolve(".omp/agent/mcp.json"), """{"mcpServers":{"global":{"command":"npx","args":["-y","tool"],"env":{"TOKEN":"secret"}}}}""")
        writeFile(home.resolve(".omp/agent/.mcp.json"), """{"mcpServers":{"hidden":{"command":"b"}}}""")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve(".omp/mcp.json"), """{"mcpServers":{"native":{"type":"http","url":"https://user:pass@example.test/mcp?key=secret"}}}""")
        writeFile(root.resolve("mcp.json"), """{"mcpServers":{"portable":{"command":"c"}}}""")
        writeFile(root.resolve(".cursor/mcp.json"), """{"mcpServers":{"cursor-only":{"command":"d"}}}""")

        val provider = OmpMcpProvider(home)
        val global = provider.discoverGlobal().associateBy { it.name }
        val project = provider.discoverProject(project(root)).associateBy { it.name }

        assertEquals(setOf("global", "hidden"), global.keys)
        assertEquals(setOf("TOKEN"), global.getValue("global").environmentVariableNames)
        assertEquals(McpScope.GLOBAL, global.getValue("global").scope)
        assertEquals(setOf("native", "portable"), project.keys)
        assertEquals(McpTransport.HTTP, project.getValue("native").transport)
        assertFalse(project.getValue("native").url.orEmpty().contains("pass"))
        assertEquals("project", project.getValue("portable").projectName)
    }

    @Test
    fun `should list native and standalone instruction files but not AGENTS md inside other dot directories`() {
        writeFile(home.resolve(".omp/agent/AGENTS.md"), "Global")
        writeFile(home.resolve(".omp/agent/RULES.md"), "Sticky")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve("AGENTS.md"), "Standalone")
        writeFile(root.resolve("pkg/AGENTS.md"), "Nested")
        writeFile(root.resolve(".omp/AGENTS.md"), "Native")
        writeFile(root.resolve(".omp/RULES.md"), "Native rules")
        writeFile(root.resolve(".claude/AGENTS.md"), "Other tool")

        val provider = OmpInstructionProvider(home)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(root))

        assertEquals(setOf(InstructionType.AGENTS_MD, InstructionType.AGENT_SPECIFIC), global.map { it.type }.toSet())
        assertTrue(global.all { it.scope == InstructionScope.GLOBAL && it.agentIds == setOf("omp") })
        assertEquals(
            setOf("AGENTS.md", "pkg/AGENTS.md", ".omp/AGENTS.md", ".omp/RULES.md"),
            project.map { relative(root, it.path) }.toSet(),
        )
        assertEquals(InstructionType.AGENT_SPECIFIC, project.single { it.path.endsWith("RULES.md") }.type)
    }

    @Test
    fun `should also list AGENTS md of the vendor-neutral dot agent and dot agents directories`() {
        writeFile(home.resolve(".agents/AGENTS.md"), "User")
        writeFile(home.resolve(".agent/AGENTS.md"), "User singular")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve(".agents/AGENTS.md"), "Project")
        writeFile(root.resolve("pkg/.agent/AGENTS.md"), "Nested singular")
        writeFile(root.resolve(".claude/AGENTS.md"), "Other tool")

        val provider = OmpInstructionProvider(home)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(root))

        assertEquals(setOf(".agents/AGENTS.md", ".agent/AGENTS.md"), global.map { relative(home, it.path) }.toSet())
        assertTrue(global.all { it.scope == InstructionScope.GLOBAL && it.type == InstructionType.AGENTS_MD })
        assertEquals(setOf(".agents/AGENTS.md", "pkg/.agent/AGENTS.md"), project.map { relative(root, it.path) }.toSet())
    }

    @Test
    fun `should inventory the YAML config files of the agent directory and the project`() {
        writeFile(home.resolve(".omp/agent/config.yml"), "modelRoles:\n  default: x\n")
        writeFile(home.resolve(".omp/agent/agent.db"), "binary")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve(".omp/config.yml"), "theme: dark\n")

        val provider = OmpConfigProvider(home)

        assertEquals(listOf("config.yml"), provider.discoverGlobal().map { Path.of(it.path).fileName.toString() })
        val projectSources = provider.discoverProject(project(root))
        assertEquals(1, projectSources.size)
        assertEquals("project", projectSources.single().projectName)
    }
}
