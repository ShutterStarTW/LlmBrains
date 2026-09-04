package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ClaudeMcpProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should discover global stdio HTTP and unknown servers without exposing secrets`() {
        Files.writeString(
            temporaryDirectory.resolve(".claude.json"),
            """
            {
              "mcpServers": {
                "playwright": {
                  "command": "npx",
                  "args": ["-y", "@playwright/mcp", "--token", "argument-secret"],
                  "env": {"API_TOKEN": "top-secret"}
                },
                "remote": {
                  "type": "streamable-http",
                  "url": "https://example.test/mcp?api_key=url-secret",
                  "headers": {"Authorization": "Bearer ${'$'}{REMOTE_TOKEN}"}
                },
                "socket": {
                  "type": "ws",
                  "url": "wss://example.test/mcp"
                },
                "invalid": {"args": ["missing-command"]}
              }
            }
            """.trimIndent(),
        )

        val servers = ClaudeMcpProvider(temporaryDirectory).discoverGlobal().associateBy { it.name }

        assertEquals(setOf("playwright", "remote", "socket"), servers.keys)
        assertEquals(McpTransport.STDIO, servers.getValue("playwright").transport)
        assertEquals(
            listOf("-y", "@playwright/mcp", "--token", "<redacted>"),
            servers.getValue("playwright").args,
        )
        assertEquals(setOf("API_TOKEN"), servers.getValue("playwright").environmentVariableNames)
        assertEquals(McpTransport.HTTP, servers.getValue("remote").transport)
        assertEquals(setOf("REMOTE_TOKEN"), servers.getValue("remote").environmentVariableNames)
        assertEquals(McpTransport.UNKNOWN, servers.getValue("socket").transport)
        assertTrue(servers.values.all { it.projectName == null })
        assertFalse(servers.toString().contains("top-secret"))
        assertFalse(servers.toString().contains("argument-secret"))
        assertFalse(servers.toString().contains("url-secret"))
    }

    @Test
    fun `should discover shared and local project servers`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"))
        Files.writeString(
            projectRoot.resolve(".mcp.json"),
            """{"mcpServers":{"shared":{"type":"http","url":"https://shared.test/mcp"}}}""",
        )
        Files.writeString(
            temporaryDirectory.resolve(".claude.json"),
            """
            {
              "projects": {
                ${json(projectRoot.toString())}: {
                  "mcpServers": {
                    "local": {"command": "local-server", "args": []}
                  }
                }
              }
            }
            """.trimIndent(),
        )

        val servers = ClaudeMcpProvider(temporaryDirectory)
            .discoverProject(project(projectRoot))
            .associateBy { it.name }

        assertEquals(setOf("shared", "local"), servers.keys)
        assertTrue(servers.values.all { it.scope == McpScope.PROJECT })
        assertTrue(servers.values.all { it.projectName == "project" })
        assertTrue(servers.getValue("shared").configPath.endsWith(".mcp.json"))
        assertTrue(servers.getValue("local").configPath.endsWith(".claude.json"))
    }

    @Test
    fun `should ignore malformed config and malformed individual servers`() {
        Files.writeString(temporaryDirectory.resolve(".claude.json"), "{unfinished")
        assertTrue(ClaudeMcpProvider(temporaryDirectory).discoverGlobal().isEmpty())

        Files.writeString(
            temporaryDirectory.resolve(".claude.json"),
            """{"mcpServers":{"valid":{"command":"server"},"missing-command":{"type":"stdio"},"not-object":"bad"}}""",
        )
        val server = ClaudeMcpProvider(temporaryDirectory).discoverGlobal().single()
        assertEquals("valid", server.name)
        assertNull(server.url)
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

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(character)
            }
        }
        append('"')
    }
}
