package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class KiloMcpProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should parse the global kilo jsonc config and keep only variable names`() {
        val directory = Files.createDirectories(temporaryDirectory.resolve(".config/kilo"))
        Files.writeString(
            directory.resolve("kilo.jsonc"),
            """
            {
              // global server
              "mcp": {
                "docs": {"type": "local", "command": ["npx", "-y", "docs-mcp"], "environment": {"API_KEY": "{env:DOCS_KEY}"}, "enabled": true},
              },
            }
            """.trimIndent(),
        )

        val server = KiloMcpProvider(temporaryDirectory).discoverGlobal().single()

        assertEquals("kilo", server.agentId)
        assertEquals("docs", server.name)
        assertEquals(McpScope.GLOBAL, server.scope)
        assertEquals(McpTransport.STDIO, server.transport)
        assertEquals("npx", server.command)
        assertEquals(setOf("API_KEY", "DOCS_KEY"), server.environmentVariableNames)
    }

    @Test
    fun `should read config json in the global directory and kilo directories in the home directory`() {
        Files.writeString(
            Files.createDirectories(temporaryDirectory.resolve(".config/kilo")).resolve("config.json"),
            """{"mcp":{"legacy":{"type":"local","command":["a"]}}}""",
        )
        Files.writeString(
            Files.createDirectories(temporaryDirectory.resolve(".kilo")).resolve("kilo.json"),
            """{"mcp":{"home-kilo":{"type":"local","command":["b"]}}}""",
        )
        Files.writeString(
            Files.createDirectories(temporaryDirectory.resolve(".kilocode")).resolve("kilo.jsonc"),
            """{"mcp":{"home-kilocode":{"type":"local","command":["c"]}}}""",
        )

        val names = KiloMcpProvider(temporaryDirectory).discoverGlobal().map { it.name }.toSet()

        assertEquals(setOf("legacy", "home-kilo", "home-kilocode"), names)
    }

    @Test
    fun `should read project root and project directory configs and redact remote secrets`() {
        val root = Files.createDirectories(temporaryDirectory.resolve("project"))
        Files.writeString(
            root.resolve("kilo.json"),
            """{"mcp":{"remote":{"type":"remote","url":"https://user:pass@example.test/mcp?token=secret","headers":{"Authorization":"Bearer {env:AUTH}"}}}}""",
        )
        Files.writeString(
            Files.createDirectories(root.resolve(".kilo")).resolve("kilo.json"),
            """{"mcp":{"local":{"type":"local","command":["tool"]}}}""",
        )

        val servers = KiloMcpProvider(temporaryDirectory).discoverProject(project(root)).associateBy { it.name }

        assertEquals(setOf("remote", "local"), servers.keys)
        assertEquals(McpScope.PROJECT, servers.getValue("remote").scope)
        assertEquals(McpTransport.HTTP, servers.getValue("remote").transport)
        assertEquals(setOf("AUTH"), servers.getValue("remote").environmentVariableNames)
        assertFalse(servers.getValue("remote").url.orEmpty().contains("pass"))
        assertEquals("project", servers.getValue("local").projectName)
    }

    @Test
    fun `should also read opencode json next to kilo json as Kilo does`() {
        // Source: Kilo's `KilocodeConfig.ALL_CONFIG_FILES` is kilo.jsonc, kilo.json, opencode.jsonc, opencode.json.
        val root = Files.createDirectories(temporaryDirectory.resolve("project"))
        Files.writeString(root.resolve("opencode.json"), """{"mcp":{"x":{"type":"local","command":["a"]}}}""")
        Files.writeString(root.resolve("kilo.json"), """{"mcp":{"y":{"type":"local","command":["b"]}}}""")
        val globalDirectory = Files.createDirectories(temporaryDirectory.resolve(".config/kilo"))
        Files.writeString(globalDirectory.resolve("opencode.jsonc"), """{"mcp":{"g":{"type":"local","command":["c"]}}}""")

        val provider = KiloMcpProvider(temporaryDirectory)

        assertEquals(setOf("x", "y"), provider.discoverProject(project(root)).mapTo(mutableSetOf()) { it.name })
        assertEquals(setOf("g"), provider.discoverGlobal().mapTo(mutableSetOf()) { it.name })
    }
}
