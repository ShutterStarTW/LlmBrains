package com.shutterstar.agenthub.environment

import com.shutterstar.agenthub.environment.config.discovery.KimiConfigProvider
import com.shutterstar.agenthub.environment.instructions.discovery.KimiInstructionProvider
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.environment.mcp.discovery.KimiMcpProvider
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.environment.skills.discovery.KimiSkillProvider
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

class KimiEnvironmentProvidersTest {
    @TempDir
    lateinit var home: Path

    private fun relative(root: Path, file: String) = root.relativize(Path.of(file)).toString().replace('\\', '/')

    @Test
    fun `should discover Kimi specific skills only, leaving the generic agents root to the shared provider`() {
        writeSkill(home.resolve(".kimi-code/skills/kimi-skill"), "kimi-skill")
        writeSkill(home.resolve(".agents/skills/generic"), "generic")
        val root = Files.createDirectories(home.resolve("project"))
        writeSkill(root.resolve(".kimi-code/skills/project-skill"), "project-skill")
        writeSkill(root.resolve(".agents/skills/shared"), "shared")

        val provider = KimiSkillProvider(home)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(root))

        assertEquals(listOf("kimi-skill"), global.map { it.name })
        assertTrue(global.all { it.agentId == "kimi" && it.scope == SkillScope.GLOBAL && !it.shared })
        assertEquals(listOf("project-skill"), project.map { it.name })
        assertEquals("project", project.single().projectName)
    }

    @Test
    fun `should read mcpServers from the data root and the project dot kimi-code directory`() {
        writeFile(home.resolve(".kimi-code/mcp.json"), """{"mcpServers":{"linear":{"url":"https://user:pass@mcp.linear.app/mcp?token=secret"}}}""")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve(".kimi-code/mcp.json"), """{"mcpServers":{"local":{"command":"npx","args":["-y","x"],"env":{"KEY":"secret"}}}}""")
        writeFile(root.resolve("mcp.json"), """{"mcpServers":{"foreign":{"command":"z"}}}""")

        val provider = KimiMcpProvider(home)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(root)).single()

        assertEquals(McpScope.GLOBAL, global.scope)
        assertEquals(McpTransport.HTTP, global.transport)
        assertFalse(global.url.orEmpty().contains("pass"))
        assertEquals("local", project.name)
        assertEquals(setOf("KEY"), project.environmentVariableNames)
        assertEquals("project", project.projectName)
    }

    @Test
    fun `should list global AGENTS and SYSTEM files plus the generic agents file and project instructions`() {
        writeFile(home.resolve(".kimi-code/AGENTS.md"), "Kimi")
        writeFile(home.resolve(".kimi-code/SYSTEM.md"), "System prompt")
        writeFile(home.resolve(".agents/AGENTS.md"), "Generic")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve("AGENTS.md"), "Project")
        writeFile(root.resolve("pkg/AGENTS.md"), "Nested")
        writeFile(root.resolve(".kimi-code/AGENTS.md"), "Native project")
        writeFile(root.resolve(".claude/AGENTS.md"), "Other tool")

        val provider = KimiInstructionProvider(home)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(root))

        assertEquals(3, global.size)
        assertTrue(global.all { it.scope == InstructionScope.GLOBAL && it.agentIds == setOf("kimi") })
        assertEquals(InstructionType.AGENT_SPECIFIC, global.single { it.path.endsWith("SYSTEM.md") }.type)
        assertEquals(setOf("AGENTS.md", "pkg/AGENTS.md", ".kimi-code/AGENTS.md"), project.map { relative(root, it.path) }.toSet())
    }

    @Test
    fun `should inventory config toml and tui toml in the data root`() {
        writeFile(home.resolve(".kimi-code/config.toml"), "default_model = \"kimi-k2\"\n")
        writeFile(home.resolve(".kimi-code/tui.toml"), "[upgrade]\nauto_install = true\n")
        writeFile(home.resolve(".kimi-code/credentials/kimi-code.json"), """{"token":"secret"}""")

        val sources = KimiConfigProvider(home).discoverGlobal()

        assertEquals(setOf("config.toml", "tui.toml"), sources.map { Path.of(it.path).fileName.toString() }.toSet())
        // Inventory only: Kimi model identifiers are not on the closed highlight grammar, and nothing else is read.
        assertTrue(sources.all { it.highlights.isEmpty() })
    }
}
