package com.shutterstar.agenthub.environment

import com.shutterstar.agenthub.environment.config.discovery.MimoConfigProvider
import com.shutterstar.agenthub.environment.instructions.discovery.MimoInstructionProvider
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.environment.mcp.discovery.MimoMcpProvider
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.environment.skills.discovery.MimoSkillProvider
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.project
import com.shutterstar.agenthub.writeFile
import com.shutterstar.agenthub.writeSkill
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MimoEnvironmentProvidersTest {
    @TempDir
    lateinit var home: Path

    private fun relative(root: Path, file: String) = root.relativize(Path.of(file)).toString().replace('\\', '/')

    @Test
    fun `should discover the config directory skills and the project dot mimocode skills but not the shared or compat roots`() {
        writeSkill(home.resolve(".config/mimocode/skills/global-skill"), "global-skill")
        writeSkill(home.resolve(".config/mimocode/skill/singular"), "singular")
        writeSkill(home.resolve(".agents/skills/generic"), "generic")
        writeSkill(home.resolve(".claude/skills/claude-only"), "claude-only")
        val root = Files.createDirectories(home.resolve("project"))
        writeSkill(root.resolve(".mimocode/skills/project-skill"), "project-skill")
        writeSkill(root.resolve(".mimocode/skill/project-singular"), "project-singular")
        writeSkill(root.resolve("pkg/.mimocode/skills/nested"), "nested")
        writeSkill(root.resolve(".agents/skills/shared"), "shared")
        writeSkill(root.resolve(".claude/skills/claude-project"), "claude-project")

        val provider = MimoSkillProvider(home)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(root))

        assertEquals(setOf("global-skill", "singular"), global.map { it.name }.toSet())
        assertTrue(global.all { it.agentId == "mimo" && it.scope == SkillScope.GLOBAL && !it.shared })
        assertEquals(setOf("project-skill", "project-singular", "nested"), project.map { it.name }.toSet())
        assertEquals("project", project.first().projectName)
    }

    @Test
    fun `should read the mcp servers of mimocode json in the config directory the project root and dot mimocode`() {
        writeFile(home.resolve(".config/mimocode/mimocode.jsonc"), """{ // comment
            "mcp": {"remote": {"type": "remote", "url": "https://user:pass@mcp.example.com/mcp?token=secret"}}}""")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve("mimocode.json"), """{"mcp":{"root-local":{"type":"local","command":["npx","-y","x"],"environment":{"KEY":"secret"}}}}""")
        writeFile(root.resolve(".mimocode/mimocode.json"), """{"mcp":{"dot-local":{"type":"local","command":["node","server.js"]}}}""")
        writeFile(root.resolve("opencode.json"), """{"mcp":{"foreign":{"type":"local","command":["z"]}}}""")

        val provider = MimoMcpProvider(home)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(root)).associateBy { it.name }

        assertEquals("remote", global.name)
        assertEquals(McpScope.GLOBAL, global.scope)
        assertEquals(McpTransport.HTTP, global.transport)
        assertFalse(global.url.orEmpty().contains("pass"))
        assertEquals(setOf("root-local", "dot-local"), project.keys)
        assertEquals(setOf("KEY"), project.getValue("root-local").environmentVariableNames)
        assertTrue(project.values.all { it.scope == McpScope.PROJECT && it.projectName == "project" })
    }

    @Test
    fun `should use the global AGENTS file or the Claude fallback and note a CLAUDE file that MiMo ignores`() {
        writeFile(home.resolve(".claude/CLAUDE.md"), "Claude global")
        val claudeOnly = MimoInstructionProvider(home).discoverGlobal().single()
        assertEquals(InstructionType.CLAUDE_MD, claudeOnly.type)

        writeFile(home.resolve(".config/mimocode/AGENTS.md"), "Mimo global")
        val agents = MimoInstructionProvider(home).discoverGlobal().single()
        assertEquals(InstructionType.AGENTS_MD, agents.type)
        assertEquals(InstructionScope.GLOBAL, agents.scope)

        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve("AGENTS.md"), "x".repeat(600))
        writeFile(root.resolve("CLAUDE.md"), "Claude project")
        writeFile(root.resolve("sparse/AGENTS.md"), "short")
        writeFile(root.resolve("sparse/CLAUDE.md"), "Claude sparse")
        writeFile(root.resolve("claude-only/CLAUDE.md"), "Claude alone")

        val project = MimoInstructionProvider(home).discoverProject(project(root)).associateBy { relative(root, it.path) }

        assertEquals(setOf("AGENTS.md", "CLAUDE.md", "sparse/AGENTS.md", "sparse/CLAUDE.md", "claude-only/CLAUDE.md"), project.keys)
        assertTrue(project.getValue("CLAUDE.md").agentNotes["mimo"].orEmpty().contains("Not used by MiMo Code"))
        assertNull(project.getValue("sparse/CLAUDE.md").agentNotes["mimo"])
        assertNull(project.getValue("claude-only/CLAUDE.md").agentNotes["mimo"])
    }

    @Test
    fun `should inventory the json configs of the config directory and the project`() {
        writeFile(home.resolve(".config/mimocode/mimocode.json"), """{"plugin":["a"]}""")
        writeFile(home.resolve(".config/mimocode/tui.json"), "{}")
        writeFile(home.resolve(".local/share/mimocode/auth.json"), """{"token":"secret"}""")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve(".mimocode/mimocode.json"), "{}")

        val provider = MimoConfigProvider(home)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(root))

        assertEquals(setOf("mimocode.json", "tui.json"), global.map { Path.of(it.path).fileName.toString() }.toSet())
        assertEquals(listOf(".mimocode/mimocode.json"), project.map { relative(root, it.path) })
    }
}
