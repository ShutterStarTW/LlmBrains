package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CursorMcpProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should discover global and project servers without retaining secrets`() {
        val globalDirectory = Files.createDirectories(temporaryDirectory.resolve(".cursor"))
        Files.writeString(
            globalDirectory.resolve("mcp.json"),
            """{"mcpServers":{"playwright":{"type":"stdio","command":"npx","args":["--token","secret-value"],"env":{"API_KEY":"secret"},"envFile":"${'$'}{env:MCP_ENV_FILE}"}}}""",
        )
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"))
        val projectCursor = Files.createDirectories(projectRoot.resolve(".cursor"))
        Files.writeString(
            projectCursor.resolve("mcp.json"),
            """{"mcpServers":{"github":{"type":"streamable-http","url":"https://example.test/mcp?token=secret","headers":{"Authorization":"Bearer ${'$'}{env:CURSOR_TOKEN}"},"auth":{"CLIENT_ID":"${'$'}{env:CURSOR_CLIENT_ID}","CLIENT_SECRET":"static-secret"}}}}""",
        )

        val provider = CursorMcpProvider(temporaryDirectory)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(projectRoot)).single()

        assertEquals(McpScope.GLOBAL, global.scope)
        assertEquals(McpTransport.STDIO, global.transport)
        assertEquals(setOf("API_KEY", "MCP_ENV_FILE"), global.environmentVariableNames)
        assertFalse(global.args.any { it.contains("secret-value") })
        assertEquals("${'$'}{env:MCP_ENV_FILE}", global.environmentFile)
        assertEquals(null, global.projectName)
        assertEquals(McpScope.PROJECT, project.scope)
        assertEquals(McpTransport.HTTP, project.transport)
        assertEquals(setOf("CURSOR_CLIENT_ID", "CURSOR_TOKEN"), project.environmentVariableNames)
        assertFalse(project.url.orEmpty().contains("secret"))
        assertTrue(project.privateConfigurationFingerprint.orEmpty().matches(Regex("[0-9a-f]{64}")))
        assertFalse(project.toString().contains("static-secret"))
        assertEquals("project", project.projectName)
    }

    @Test
    fun `should discover default custom and inline local plugin MCP servers`() {
        val agentPlugin = Files.createDirectories(temporaryDirectory.resolve(".cursor/plugins/local/agent-plugin"))
        Files.writeString(
            agentPlugin.resolve("plugin.json"),
            """{"${'$'}schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"agent-plugin"}""",
        )
        Files.writeString(
            agentPlugin.resolve("mcp.json"),
            """{"mcpServers":{"agent-server":{"type":"stdio","command":"agent-tool"}}}""",
        )

        val customPlugin = Files.createDirectories(temporaryDirectory.resolve(".cursor/plugins/local/custom-plugin"))
        Files.createDirectories(customPlugin.resolve(".cursor-plugin"))
        Files.writeString(
            customPlugin.resolve(".cursor-plugin/plugin.json"),
            """{"name":"custom-plugin","mcpServers":["config/custom.json",{"inline-server":{"url":"https://example.test/mcp"}}]}""",
        )
        val config = Files.createDirectories(customPlugin.resolve("config"))
        Files.writeString(
            config.resolve("custom.json"),
            """{"mcpServers":{"custom-server":{"command":"custom-tool"}}}""",
        )

        val servers = CursorMcpProvider(temporaryDirectory).discoverGlobal().associateBy { it.name }

        assertEquals(setOf("agent-server", "custom-server", "inline-server"), servers.keys)
        assertTrue(servers.values.all { it.scope == McpScope.GLOBAL })
    }

    @Test
    fun `should ignore malformed oversized and escaping plugin configs`() {
        val cursorDirectory = Files.createDirectories(temporaryDirectory.resolve(".cursor"))
        Files.writeString(cursorDirectory.resolve("mcp.json"), "{unfinished")
        val plugin = Files.createDirectories(temporaryDirectory.resolve(".cursor/plugins/local/broken-plugin"))
        Files.createDirectories(plugin.resolve(".cursor-plugin"))
        Files.writeString(
            plugin.resolve(".cursor-plugin/plugin.json"),
            """{"name":"broken-plugin","mcpServers":"../outside.json"}""",
        )
        Files.writeString(temporaryDirectory.resolve(".cursor/plugins/local/outside.json"), """{"mcpServers":{}}""")
        val oversized = Files.createDirectories(temporaryDirectory.resolve(".cursor/plugins/local/oversized-plugin"))
        Files.writeString(
            oversized.resolve("plugin.json"),
            """{"${'$'}schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"oversized-plugin"}""",
        )
        Files.write(oversized.resolve("mcp.json"), ByteArray(8 * 1024 * 1024 + 1) { 'x'.code.toByte() })

        assertTrue(CursorMcpProvider(temporaryDirectory).discoverGlobal().isEmpty())
    }

    @Test
    fun `should reject plugin config paths escaping through symlinks`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val outside = Files.createDirectories(temporaryDirectory.resolve("outside"))
        Files.writeString(
            outside.resolve("mcp.json"),
            """{"mcpServers":{"outside-server":{"command":"outside-tool"}}}""",
        )
        val plugin = Files.createDirectories(temporaryDirectory.resolve(".cursor/plugins/local/linked-plugin"))
        Files.createDirectories(plugin.resolve(".cursor-plugin"))
        Files.writeString(
            plugin.resolve(".cursor-plugin/plugin.json"),
            """{"name":"linked-plugin","mcpServers":"linked/mcp.json"}""",
        )
        Files.createSymbolicLink(plugin.resolve("linked"), outside)

        assertTrue(CursorMcpProvider(temporaryDirectory).discoverGlobal().isEmpty())
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
