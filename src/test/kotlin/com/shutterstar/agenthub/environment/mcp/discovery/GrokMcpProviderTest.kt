package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GrokMcpProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should discover global stdio and HTTP servers from TOML`() {
        Files.writeString(
            grokDirectory().resolve("config.toml").also { Files.createDirectories(it.parent) },
            """
            [models]
            default = "grok-build"

            [mcp_servers.filesystem]
            command = "npx"
            args = ["-y", "@modelcontextprotocol/server-filesystem", "/allowed/directory"]
            env = { API_KEY = "never-expose-this" }

            [mcp_servers.linear]
            url = "https://mcp.linear.test/mcp?token=leak-token"
            headers = { "Authorization" = "Bearer ${'$'}{LINEAR_API_KEY}", "x-mcp-session-id" = "{{session_id}}" }
            """.trimIndent(),
        )

        val servers = GrokMcpProvider(grokDirectory()).discoverGlobal().associateBy { it.name }

        assertEquals(setOf("filesystem", "linear"), servers.keys)
        assertEquals("grok", servers.getValue("filesystem").agentId)
        assertEquals(McpTransport.STDIO, servers.getValue("filesystem").transport)
        assertEquals(setOf("API_KEY"), servers.getValue("filesystem").environmentVariableNames)
        assertEquals(McpTransport.HTTP, servers.getValue("linear").transport)
        assertEquals(setOf("LINEAR_API_KEY"), servers.getValue("linear").environmentVariableNames)
        assertTrue(servers.values.all { it.projectName == null })
        assertEquals("https://mcp.linear.test/mcp?token=<redacted>", servers.getValue("linear").url)
        assertFalse(servers.toString().contains("never-expose-this"))
        assertFalse(servers.toString().contains("leak-token"))
    }

    @Test
    fun `should discover project config and preserve project name`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"))
        val config = projectRoot.resolve(".grok/config.toml")
        Files.createDirectories(config.parent)
        Files.writeString(
            config,
            """
            [mcp_servers."docs.server"]
            url = "https://docs.test/mcp"
            """.trimIndent(),
        )

        val server = GrokMcpProvider(grokDirectory())
            .discoverProject(project(projectRoot))
            .single()

        assertEquals("docs.server", server.name)
        assertEquals(McpScope.PROJECT, server.scope)
        assertEquals("project", server.projectName)
        assertEquals("grok", server.agentId)
    }

    @Test
    fun `should skip malformed individual server and missing endpoint`() {
        Files.writeString(
            grokDirectory().resolve("config.toml").also { Files.createDirectories(it.parent) },
            """
            [mcp_servers.valid]
            command = "valid-server"

            [mcp_servers.broken]
            command = "broken-server"
            env = { BROKEN }

            [mcp_servers.missing]
            args = ["no-command"]
            """.trimIndent(),
        )

        val servers = GrokMcpProvider(grokDirectory()).discoverGlobal()

        assertEquals(listOf("valid"), servers.map { it.name })
    }

    @Test
    fun `should fail gracefully for malformed TOML`() {
        Files.writeString(
            grokDirectory().resolve("config.toml").also { Files.createDirectories(it.parent) },
            """
            [mcp_servers.unfinished]
            args = ["never-closed"
            """.trimIndent(),
        )

        assertTrue(GrokMcpProvider(grokDirectory()).discoverGlobal().isEmpty())
    }

    private fun grokDirectory(): Path = temporaryDirectory.resolve(".grok")

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
