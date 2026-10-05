package com.shutterstar.agenthub.environment

import com.shutterstar.agenthub.environment.config.discovery.FreebuffConfigProvider
import com.shutterstar.agenthub.environment.instructions.discovery.FreebuffInstructionProvider
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.environment.mcp.discovery.FreebuffMcpProvider
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.environment.skills.discovery.FreebuffSkillProvider
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.project
import com.shutterstar.agenthub.writeFile
import com.shutterstar.agenthub.writeSkill
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FreebuffEnvironmentProvidersTest {
    @TempDir
    lateinit var home: Path

    private fun relative(root: Path, file: String) = root.relativize(Path.of(file)).toString().replace('\\', '/')

    @Test
    fun `should list the Claude compatibility skill roots and leave the agents roots to the shared provider`() {
        writeSkill(home.resolve(".claude/skills/claude-skill"), "claude-skill")
        writeSkill(home.resolve(".agents/skills/generic"), "generic")
        val root = Files.createDirectories(home.resolve("project"))
        writeSkill(root.resolve(".claude/skills/project-skill"), "project-skill")
        writeSkill(root.resolve(".agents/skills/shared"), "shared")

        val provider = FreebuffSkillProvider(home)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(root))

        assertEquals(listOf("claude-skill"), global.map { it.name })
        assertTrue(global.all { it.agentId == "freebuff" && it.scope == SkillScope.GLOBAL && !it.shared })
        assertEquals(listOf("project-skill"), project.map { it.name })
    }

    @Test
    fun `should read mcp json of the agents directories of the project its parent and the home`() {
        writeFile(home.resolve(".agents/mcp.json"), """{"mcpServers":{"global":{"command":"g"}}}""")
        val workspace = Files.createDirectories(home.resolve("workspace"))
        val root = Files.createDirectories(workspace.resolve("project"))
        writeFile(root.resolve(".agents/mcp.json"), """{"mcpServers":{"local":{"command":"npx","args":["-y","x"],"env":{"KEY":"secret"}},"remote":{"type":"http","url":"https://user:pass@h.example/mcp","headers":{"X-Key":"${'$'}{REMOTE_KEY}"}}}}""")
        writeFile(workspace.resolve(".agents/mcp.json"), """{"mcpServers":{"monorepo":{"command":"m"}}}""")
        writeFile(root.resolve("mcp.json"), """{"mcpServers":{"foreign":{"command":"z"}}}""")

        val provider = FreebuffMcpProvider(home)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(root)).associateBy { it.name }

        assertEquals("global", global.name)
        assertEquals(McpScope.GLOBAL, global.scope)
        assertEquals(setOf("local", "remote", "monorepo"), project.keys)
        assertEquals(setOf("KEY"), project.getValue("local").environmentVariableNames)
        assertEquals(McpTransport.HTTP, project.getValue("remote").transport)
        assertTrue("REMOTE_KEY" in project.getValue("remote").environmentVariableNames)
        assertTrue(project.values.all { it.scope == McpScope.PROJECT && it.projectName == "project" })
    }

    @Test
    fun `should not read the home agents directory twice for a project directly below the home`() {
        writeFile(home.resolve(".agents/mcp.json"), """{"mcpServers":{"global":{"command":"g"}}}""")
        val root = Files.createDirectories(home.resolve("project"))

        assertTrue(FreebuffMcpProvider(home).discoverProject(project(root)).isEmpty())
    }

    @Test
    fun `should list knowledge files and note a CLAUDE file next to an AGENTS file`() {
        writeFile(home.resolve(".CLAUDE.md"), "Claude global")
        assertEquals(InstructionType.CLAUDE_MD, FreebuffInstructionProvider(home).discoverGlobal().single().type)
        writeFile(home.resolve(".AGENTS.md"), "Agents global")
        val global = FreebuffInstructionProvider(home).discoverGlobal().single()
        assertEquals(InstructionType.AGENTS_MD, global.type)
        assertEquals(InstructionScope.GLOBAL, global.scope)

        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve("AGENTS.md"), "Project")
        writeFile(root.resolve("CLAUDE.md"), "Claude project")
        writeFile(root.resolve("pkg/CLAUDE.md"), "Claude alone")
        writeFile(root.resolve("docs/auth.knowledge.md"), "Knowledge")
        writeFile(root.resolve("docs/readme.md"), "Not a knowledge file")

        val project = FreebuffInstructionProvider(home).discoverProject(project(root)).associateBy { relative(root, it.path) }

        assertEquals(setOf("AGENTS.md", "CLAUDE.md", "pkg/CLAUDE.md", "docs/auth.knowledge.md"), project.keys)
        assertTrue(project.getValue("CLAUDE.md").agentNotes["freebuff"].orEmpty().contains("Not used by Freebuff"))
        assertNull(project.getValue("pkg/CLAUDE.md").agentNotes["freebuff"])
        assertEquals(InstructionType.AGENT_SPECIFIC, project.getValue("docs/auth.knowledge.md").type)
    }

    @Test
    fun `should inventory settings json only and never the credentials`() {
        writeFile(home.resolve(".config/manicode/settings.json"), """{"mode":"DEFAULT","freebuffModel":"x"}""")
        writeFile(home.resolve(".config/manicode/credentials.json"), """{"authToken":"secret"}""")

        val global = FreebuffConfigProvider(home).discoverGlobal()

        assertEquals(listOf("settings.json"), global.map { Path.of(it.path).fileName.toString() })
        assertTrue(global.all { it.highlights.isEmpty() })
    }
}
