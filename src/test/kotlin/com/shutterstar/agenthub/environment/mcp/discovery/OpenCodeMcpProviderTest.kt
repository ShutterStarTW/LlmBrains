package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.project
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OpenCodeMcpProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should read opencode json of the home dot opencode config directory`() {
        val directory = Files.createDirectories(temporaryDirectory.resolve(".opencode"))
        Files.writeString(directory.resolve("opencode.json"), """{"mcp":{"home":{"type":"local","command":["a"]}}}""")

        val servers = OpenCodeMcpProvider(temporaryDirectory).discoverGlobal()

        assertEquals(listOf("home"), servers.map { it.name })
        assertEquals(McpScope.GLOBAL, servers.single().scope)
    }

    @Test
    fun `should parse global JSONC and current direct MCP format`() {
        val configDirectory = Files.createDirectories(temporaryDirectory.resolve(".config/opencode"))
        Files.writeString(
            configDirectory.resolve("opencode.jsonc"),
            """
            {
              // Global local server
              "mcp": {
                "playwright": {
                  "type": "local",
                  "command": ["npx", "-y", "@playwright/mcp"],
                  "environment": {"MCP_TOKEN": "{env:MCP_TOKEN}"},
                },
              },
            }
            """.trimIndent(),
        )

        val server = OpenCodeMcpProvider(temporaryDirectory).discoverGlobal().single()

        assertEquals("playwright", server.name)
        assertEquals(McpScope.GLOBAL, server.scope)
        assertEquals(McpTransport.STDIO, server.transport)
        assertEquals("npx", server.command)
        assertEquals(listOf("-y", "@playwright/mcp"), server.args)
        assertEquals(setOf("MCP_TOKEN"), server.environmentVariableNames)
        assertEquals(null, server.projectName)
    }

    @Test
    fun `should parse project V2 server container and redact remote secrets`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"))
        Files.writeString(
            projectRoot.resolve("opencode.json"),
            """{"mcp":{"servers":{"context":{"type":"remote","url":"https://user:pass@example.test/mcp?api_key=secret","headers":{"X-Key":"{env:CONTEXT_KEY}"},"oauth":{"clientId":"{env:MCP_CLIENT_ID}","clientSecret":"{env:MCP_CLIENT_SECRET}"}}}}}""",
        )

        val server = OpenCodeMcpProvider(temporaryDirectory).discoverProject(project(projectRoot)).single()

        assertEquals(McpScope.PROJECT, server.scope)
        assertEquals(McpTransport.HTTP, server.transport)
        assertEquals(setOf("CONTEXT_KEY", "MCP_CLIENT_ID", "MCP_CLIENT_SECRET"), server.environmentVariableNames)
        assertFalse(server.url.orEmpty().contains("pass"))
        assertFalse(server.url.orEmpty().contains("secret"))
        assertEquals("project", server.projectName)
    }

    @Test
    fun `should read both the project root config and the dot-opencode directory config`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"))
        Files.writeString(
            projectRoot.resolve("opencode.json"),
            """{"mcp":{"root-server":{"type":"local","command":["root-cmd"]}}}""",
        )
        val dotDirectory = Files.createDirectories(projectRoot.resolve(".opencode"))
        Files.writeString(
            dotDirectory.resolve("opencode.jsonc"),
            """{"mcp":{"dot-server":{"type":"local","command":["dot-cmd"]}}}""",
        )

        val servers = OpenCodeMcpProvider(temporaryDirectory).discoverProject(project(projectRoot))

        assertEquals(setOf("root-server", "dot-server"), servers.map { it.name }.toSet())
        assertTrue(servers.all { it.scope == McpScope.PROJECT && it.projectName == "project" })
    }
}
