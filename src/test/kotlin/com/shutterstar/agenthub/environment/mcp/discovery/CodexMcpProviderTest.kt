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

class CodexMcpProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should discover global stdio and HTTP servers from TOML`() {
        Files.writeString(
            temporaryDirectory.resolve("config.toml"),
            """
            model = "gpt-test"

            [mcp_servers.context7]
            command = "npx"
            args = [
              "-y",
              "@upstash/context7-mcp"
            ]
            env_vars = ["LOCAL_TOKEN", { name = "REMOTE_TOKEN", source = "remote" }]

            [mcp_servers.context7.env]
            API_SECRET = "never-expose-this"

            [mcp_servers.figma]
            url = "https://mcp.figma.test/mcp"
            bearer_token_env_var = "FIGMA_TOKEN"
            env_http_headers = { "X-Token" = "HEADER_TOKEN" }
            http_headers = { "Authorization" = "Bearer static-secret" }
            """.trimIndent(),
        )

        val servers = CodexMcpProvider(temporaryDirectory).discoverGlobal().associateBy { it.name }

        assertEquals(setOf("context7", "figma"), servers.keys)
        assertEquals(McpTransport.STDIO, servers.getValue("context7").transport)
        assertEquals(listOf("-y", "@upstash/context7-mcp"), servers.getValue("context7").args)
        assertEquals(
            setOf("API_SECRET", "LOCAL_TOKEN", "REMOTE_TOKEN"),
            servers.getValue("context7").environmentVariableNames,
        )
        assertEquals(McpTransport.HTTP, servers.getValue("figma").transport)
        assertEquals(setOf("FIGMA_TOKEN", "HEADER_TOKEN"), servers.getValue("figma").environmentVariableNames)
        assertTrue(servers.values.all { it.projectName == null })
        assertFalse(servers.toString().contains("never-expose-this"))
        assertFalse(servers.toString().contains("static-secret"))
    }

    @Test
    fun `should discover quoted server name and project config`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"))
        val codexDirectory = Files.createDirectories(projectRoot.resolve(".codex"))
        Files.writeString(
            codexDirectory.resolve("config.toml"),
            """
            [mcp_servers."docs.server"]
            url = "https://docs.test/mcp"
            """.trimIndent(),
        )

        val server = CodexMcpProvider(temporaryDirectory)
            .discoverProject(project(projectRoot))
            .single()

        assertEquals("docs.server", server.name)
        assertEquals(McpScope.PROJECT, server.scope)
        assertEquals("project", server.projectName)
    }

    @Test
    fun `should skip malformed individual server and missing endpoint`() {
        Files.writeString(
            temporaryDirectory.resolve("config.toml"),
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

        val servers = CodexMcpProvider(temporaryDirectory).discoverGlobal()

        assertEquals(listOf("valid"), servers.map { it.name })
    }

    @Test
    fun `should fail gracefully for malformed TOML`() {
        Files.writeString(
            temporaryDirectory.resolve("config.toml"),
            """
            [mcp_servers.unfinished]
            args = ["never-closed"
            """.trimIndent(),
        )

        assertTrue(CodexMcpProvider(temporaryDirectory).discoverGlobal().isEmpty())
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
