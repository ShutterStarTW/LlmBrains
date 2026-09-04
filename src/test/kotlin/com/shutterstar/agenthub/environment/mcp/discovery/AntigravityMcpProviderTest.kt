package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AntigravityMcpProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should discover global and workspace MCP configurations without retaining secrets`() {
        val globalDirectory = Files.createDirectories(temporaryDirectory.resolve(".gemini/config"))
        Files.writeString(
            globalDirectory.resolve("mcp_config.json"),
            """{"mcpServers":{"local":{"command":"npx","args":["--token","secret"],"env":{"API_KEY":"secret"}}}}""",
        )
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"))
        val projectConfig = Files.createDirectories(projectRoot.resolve(".agents"))
        Files.writeString(
            projectConfig.resolve("mcp_config.json"),
            """{"mcpServers":{"remote":{"serverUrl":"https://user:pass@example.test/mcp?token=secret","headers":{"Authorization":"Bearer ${'$'}{AUTH_TOKEN}"}}}}""",
        )

        val provider = AntigravityMcpProvider(temporaryDirectory)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(projectRoot)).single()

        assertEquals(McpScope.GLOBAL, global.scope)
        assertEquals(McpTransport.STDIO, global.transport)
        assertEquals(setOf("API_KEY"), global.environmentVariableNames)
        assertFalse(global.args.any { it.contains("secret") })
        assertEquals(null, global.projectName)
        assertEquals(McpScope.PROJECT, project.scope)
        assertEquals(McpTransport.HTTP, project.transport)
        assertEquals(setOf("AUTH_TOKEN"), project.environmentVariableNames)
        assertFalse(project.url.orEmpty().contains("pass"))
        assertFalse(project.url.orEmpty().contains("secret"))
        assertEquals("project", project.projectName)
    }

    @Test
    fun `should discover plugin MCP configurations, bare configs, and tolerant jsonc comments`() {
        val cliDirectory = Files.createDirectories(temporaryDirectory.resolve(".gemini/antigravity-cli"))
        Files.writeString(
            cliDirectory.resolve("mcp_config.json"),
            """
            // Comment in jsonc
            {
                /* Bare server without mcpServers wrapper */
                "cli-helper": {
                    "command": "node",
                    "args": ["helper.js"]
                }
            }
            """.trimIndent(),
        )
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("project-with-plugin"))
        val pluginDir = Files.createDirectories(projectRoot.resolve(".agents/plugins/my-plugin"))
        Files.writeString(
            pluginDir.resolve("mcp_config.json"),
            """{"mcpServers":{"plugin-server":{"command":"python","args":["server.py"]}}}""",
        )

        val provider = AntigravityMcpProvider(temporaryDirectory)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(projectRoot)).single()

        assertEquals("cli-helper", global.name)
        assertEquals(McpScope.GLOBAL, global.scope)
        assertEquals("plugin-server", project.name)
        assertEquals(McpScope.PROJECT, project.scope)
        assertEquals("project-with-plugin", project.projectName)
    }

    private fun project(root: Path): DiscoveredProject = DiscoveredProject(
        identity = ProjectIdentity(root.fileName.toString(), root.toString(), root.toString(), null),
        name = root.fileName.toString(),
        path = root.toString(),
        gitRoot = root.toString(),
        gitRemote = null,
        currentBranch = null,
        agents = emptyList(),
        lastActivity = null,
    )
}
