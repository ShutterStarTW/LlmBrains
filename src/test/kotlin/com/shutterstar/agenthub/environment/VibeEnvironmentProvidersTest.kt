package com.shutterstar.agenthub.environment

import com.shutterstar.agenthub.environment.config.discovery.VibeConfigProvider
import com.shutterstar.agenthub.environment.instructions.discovery.VibeInstructionProvider
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.mcp.discovery.VibeMcpProvider
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.environment.skills.discovery.VibeSkillProvider
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

class VibeEnvironmentProvidersTest {
    @TempDir
    lateinit var home: Path

    private fun relative(root: Path, file: String) = root.relativize(Path.of(file)).toString().replace('\\', '/')

    @Test
    fun `should discover the vibe skills only, leaving the agents roots to the shared provider`() {
        writeSkill(home.resolve(".vibe/skills/vibe-skill"), "vibe-skill")
        writeSkill(home.resolve(".agents/skills/generic"), "generic")
        val root = Files.createDirectories(home.resolve("project"))
        writeSkill(root.resolve(".vibe/skills/project-skill"), "project-skill")
        writeSkill(root.resolve("pkg/.vibe/skills/nested"), "nested")
        writeSkill(root.resolve(".agents/skills/shared"), "shared")

        val provider = VibeSkillProvider(home)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(root))

        assertEquals(listOf("vibe-skill"), global.map { it.name })
        assertTrue(global.all { it.agentId == "vibe" && it.scope == SkillScope.GLOBAL && !it.shared })
        // Vibe inspects the project root only.
        assertEquals(listOf("project-skill"), project.map { it.name })
        assertEquals("project", project.single().projectName)
    }

    @Test
    fun `should read every mcp_servers entry of the user and the project config toml`() {
        writeFile(
            home.resolve(".vibe/config.toml"),
            """
            active_model = "devstral-small"

            [[mcp_servers]]
            name = "files"
            transport = "stdio"
            command = "npx"
            args = ["-y", "@scope/files-mcp"]
            env = { TOKEN = "secret-value", OTHER = "x" }

            [[mcp_servers]]
            name = "remote"
            transport = "streamable-http"
            url = "https://user:pass@mcp.example.com/mcp?token=secret"

            [mcp_servers.auth]
            type = "static"
            api_key_env = "REMOTE_API_KEY"
            [mcp_servers.auth.headers]
            X-Trace = "${'$'}{TRACE_ID}"

            [tools.bash]
            permission = "ask"
            """.trimIndent(),
        )
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(
            root.resolve(".vibe/config.toml"),
            """
            [[mcp_servers]]
            name = "listed"
            transport = "stdio"
            command = ["node", "server.js"]
            args = ["--flag"]
            [mcp_servers.env]
            KEY = "secret"
            """.trimIndent(),
        )

        val provider = VibeMcpProvider(home)
        val global = provider.discoverGlobal().associateBy { it.name }
        val project = provider.discoverProject(project(root)).single()

        assertEquals(setOf("files", "remote"), global.keys)
        val files = global.getValue("files")
        assertEquals(McpTransport.STDIO, files.transport)
        assertEquals("npx", files.command)
        assertEquals(listOf("-y", "@scope/files-mcp"), files.args)
        assertEquals(setOf("TOKEN", "OTHER"), files.environmentVariableNames)
        assertEquals(McpScope.GLOBAL, files.scope)
        val remote = global.getValue("remote")
        assertEquals(McpTransport.HTTP, remote.transport)
        assertFalse(remote.url.orEmpty().contains("pass"))
        assertEquals(setOf("REMOTE_API_KEY", "TRACE_ID"), remote.environmentVariableNames)
        assertEquals("listed", project.name)
        assertEquals("node", project.command)
        assertEquals(listOf("server.js", "--flag"), project.args)
        assertEquals(setOf("KEY"), project.environmentVariableNames)
        assertEquals(McpScope.PROJECT, project.scope)
        assertEquals("project", project.projectName)
    }

    @Test
    fun `should ignore entries without a name, tables of other settings and a malformed file`() {
        writeFile(
            home.resolve(".vibe/config.toml"),
            """
            [[mcp_servers]]
            transport = "stdio"
            command = "nameless"

            [[providers]]
            name = "mistral"
            api_base = "https://api.mistral.ai/v1"

            [[mcp_servers]]
            name = "kept"
            command = "ok"
            """.trimIndent(),
        )
        assertEquals(listOf("kept"), VibeMcpProvider(home).discoverGlobal().map { it.name })

        writeFile(home.resolve(".vibe/config.toml"), "[[mcp_servers]\nname = ")
        assertTrue(VibeMcpProvider(home).discoverGlobal().isEmpty())
    }

    @Test
    fun `should list the global AGENTS file and the AGENTS files of the project without dot directories`() {
        writeFile(home.resolve(".vibe/AGENTS.md"), "Global")
        writeFile(home.resolve(".vibe/prompts/custom.md"), "Prompt")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve("AGENTS.md"), "Project")
        writeFile(root.resolve("pkg/AGENTS.md"), "Nested")
        writeFile(root.resolve(".claude/AGENTS.md"), "Other tool")
        writeFile(root.resolve("CLAUDE.md"), "Not a Vibe file")

        val provider = VibeInstructionProvider(home)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(root))

        assertEquals(InstructionScope.GLOBAL, global.scope)
        assertEquals(setOf("AGENTS.md", "pkg/AGENTS.md"), project.map { relative(root, it.path) }.toSet())
        assertTrue(project.all { it.agentIds == setOf("vibe") && it.projectName == "project" })
    }

    @Test
    fun `should inventory config toml only and never the env file or trusted folders`() {
        writeFile(home.resolve(".vibe/config.toml"), "active_model = \"devstral-small\"\n")
        writeFile(home.resolve(".vibe/.env"), "MISTRAL_API_KEY=secret\n")
        writeFile(home.resolve(".vibe/trusted_folders.toml"), "trusted = []\n")
        val root = Files.createDirectories(home.resolve("project"))
        writeFile(root.resolve(".vibe/config.toml"), "enable_telemetry = false\n")

        val provider = VibeConfigProvider(home)
        val global = provider.discoverGlobal()
        val project = provider.discoverProject(project(root))

        assertEquals(listOf("config.toml"), global.map { Path.of(it.path).fileName.toString() })
        assertEquals(listOf(".vibe/config.toml"), project.map { relative(root, it.path) })
        assertTrue(global.all { it.highlights.isEmpty() })
        assertNull(global.firstOrNull { it.path.endsWith(".env") })
    }
}
