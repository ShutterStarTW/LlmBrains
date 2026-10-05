package com.shutterstar.agenthub.environment

import com.shutterstar.agenthub.environment.config.discovery.JunieConfigProvider
import com.shutterstar.agenthub.environment.instructions.discovery.JunieInstructionProvider
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.environment.mcp.discovery.JunieMcpProvider
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.environment.skills.discovery.JunieSkillProvider
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

class JunieEnvironmentProvidersTest {
    @TempDir
    lateinit var home: Path

    private fun relative(root: Path, file: String) = root.relativize(Path.of(file)).toString().replace('\\', '/')

    @Test
    fun `should discover the junie skills only, leaving the agents roots to the shared provider`() {
        writeSkill(home.resolve(".junie/skills/junie-skill"), "junie-skill")
        writeSkill(home.resolve(".agents/skills/generic"), "generic")
        val root = Files.createDirectories(home.resolve("project"))
        writeSkill(root.resolve(".junie/skills/project-skill"), "project-skill")
        writeSkill(root.resolve(".agents/skills/shared"), "shared")

        val provider = JunieSkillProvider(home)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(root))

        assertEquals(listOf("junie-skill"), global.map { it.name })
        assertTrue(global.all { it.agentId == "junie" && it.scope == SkillScope.GLOBAL && !it.shared })
        assertEquals(listOf("project-skill"), project.map { it.name })
        assertEquals("project", project.single().projectName)
    }

    @Test
    fun `should read mcpServers from junie mcp mcp json of the home and the project`() {
        writeFile(home.resolve(".junie/mcp/mcp.json"), """{"mcpServers":{"linear":{"url":"https://user:pass@mcp.linear.app/mcp?token=secret","headers":{"Authorization":"Bearer ${'$'}{LINEAR_TOKEN}"}}}}""")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve(".junie/mcp/mcp.json"), """{"mcpServers":{"local":{"command":"npx","args":["-y","x"],"env":{"KEY":"secret"}}}}""")
        writeFile(root.resolve(".junie/mcp.json"), """{"mcpServers":{"misplaced":{"command":"z"}}}""")
        writeFile(root.resolve("mcp.json"), """{"mcpServers":{"foreign":{"command":"z"}}}""")

        val provider = JunieMcpProvider(home)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(root)).single()

        assertEquals(McpScope.GLOBAL, global.scope)
        assertEquals(McpTransport.HTTP, global.transport)
        assertFalse(global.url.orEmpty().contains("pass"))
        assertTrue("LINEAR_TOKEN" in global.environmentVariableNames)
        assertEquals("local", project.name)
        assertEquals(setOf("KEY"), project.environmentVariableNames)
        assertEquals("project", project.projectName)
    }

    @Test
    fun `should list the global AGENTS file and every project guideline file without choosing between them`() {
        writeFile(home.resolve(".junie/AGENTS.md"), "Global")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve(".junie/AGENTS.md"), "Junie")
        writeFile(root.resolve("AGENTS.md"), "Root")
        writeFile(root.resolve("pkg/AGENTS.md"), "Nested (not read by Junie)")
        writeFile(root.resolve(".junie/playbook.md"), "Playbook")
        writeFile(root.resolve(".junie/rules/style.md"), "Rule")
        writeFile(root.resolve(".junie/rules/notes.txt"), "Not markdown")
        writeFile(root.resolve(".junie/guidelines.md"), "Legacy file")
        writeFile(root.resolve(".junie/guidelines/extra.md"), "Legacy folder")

        val provider = JunieInstructionProvider(home)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(root)).associateBy { relative(root, it.path) }

        assertEquals(InstructionScope.GLOBAL, global.scope)
        assertEquals(
            setOf(".junie/AGENTS.md", "AGENTS.md", ".junie/playbook.md", ".junie/rules/style.md", ".junie/guidelines.md", ".junie/guidelines/extra.md"),
            project.keys,
        )
        assertEquals(InstructionType.AGENTS_MD, project.getValue(".junie/AGENTS.md").type)
        assertEquals(InstructionType.AGENT_SPECIFIC, project.getValue(".junie/rules/style.md").type)
        assertTrue(project.values.all { it.agentNotes.isEmpty() })
    }

    @Test
    fun `should inventory the config files and never credentials`() {
        writeFile(home.resolve(".junie/config.json"), """{"model":"x"}""")
        writeFile(home.resolve(".junie/settings.json"), """{"braveMode":"AUTO"}""")
        writeFile(home.resolve(".junie/secure_credentials.json"), """{"token":"secret"}""")
        writeFile(home.resolve(".junie/authentication-key"), "secret")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve(".junie/config.json"), "{}")

        val provider = JunieConfigProvider(home)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(root))

        assertEquals(setOf("config.json", "settings.json"), global.map { Path.of(it.path).fileName.toString() }.toSet())
        assertEquals(listOf(".junie/config.json"), project.map { relative(root, it.path) })
    }
}
