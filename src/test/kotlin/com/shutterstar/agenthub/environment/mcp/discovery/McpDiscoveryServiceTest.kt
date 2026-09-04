package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpConsistency
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpDiscoveryServiceTest {
    private val service = McpDiscoveryService(providers = emptyList())

    @Test
    fun `should merge identical server configurations across agents`() {
        val server = service.normalize(
            listOf(
                raw(agentId = "claude", configPath = "/claude.json"),
                raw(agentId = "codex", configPath = "/config.toml"),
            ),
        ).single()

        assertEquals(McpConsistency.IDENTICAL, server.consistency)
        assertEquals(setOf("claude", "codex"), server.sources.mapTo(mutableSetOf()) { it.agentId })
        assertEquals(2, server.sources.size)
    }

    @Test
    fun `should flag different configurations sharing a server name`() {
        val server = service.normalize(
            listOf(
                raw(agentId = "claude", configPath = "/claude.json"),
                raw(
                    agentId = "codex",
                    configPath = "/config.toml",
                    transport = McpTransport.HTTP,
                    command = null,
                    args = emptyList(),
                    url = "http://localhost:3001/mcp",
                ),
            ),
        ).single()

        assertEquals(McpConsistency.DIFFERENT, server.consistency)
        assertEquals(2, server.sources.size)
    }

    @Test
    fun `should not merge different names with the same endpoint`() {
        val servers = service.normalize(
            listOf(
                raw(agentId = "claude", configPath = "/claude.json", name = "first"),
                raw(agentId = "codex", configPath = "/config.toml", name = "second"),
            ),
        )

        assertEquals(2, servers.size)
        assertTrue(servers.all { it.consistency == McpConsistency.SINGLE_SOURCE })
    }

    @Test
    fun `should keep global and project server definitions separate`() {
        val servers = service.normalize(
            listOf(
                raw(agentId = "claude", configPath = "/global.json"),
                raw(agentId = "claude", configPath = "/project.json", scope = McpScope.PROJECT),
            ),
        )

        assertEquals(2, servers.size)
        assertNotEquals(servers[0].id, servers[1].id)
        assertEquals(setOf(McpScope.GLOBAL, McpScope.PROJECT), servers.mapTo(mutableSetOf()) { it.scope })
    }

    @Test
    fun `should union environment variable names without values`() {
        val server = service.normalize(
            listOf(
                raw(
                    agentId = "claude",
                    configPath = "/claude.json",
                    environmentVariableNames = setOf("CLAUDE_TOKEN"),
                ),
                raw(
                    agentId = "codex",
                    configPath = "/config.toml",
                    environmentVariableNames = setOf("CODEX_TOKEN"),
                ),
            ),
        ).single()

        assertEquals(setOf("CLAUDE_TOKEN", "CODEX_TOKEN"), server.environmentVariableNames)
        assertEquals(McpConsistency.DIFFERENT, server.consistency)
        assertTrue(server.id.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `should flag configurations with different environment files`() {
        val server = service.normalize(
            listOf(
                raw(agentId = "cursor", configPath = "/first.json", environmentFile = ".env"),
                raw(agentId = "cursor", configPath = "/second.json", environmentFile = ".env.local"),
            ),
        ).single()

        assertEquals(McpConsistency.DIFFERENT, server.consistency)
    }

    @Test
    fun `should flag configurations with different private authentication fingerprints`() {
        val server = service.normalize(
            listOf(
                raw(agentId = "cursor", configPath = "/first.json", privateConfigurationFingerprint = "first"),
                raw(agentId = "cursor", configPath = "/second.json", privateConfigurationFingerprint = "second"),
            ),
        ).single()

        assertEquals(McpConsistency.DIFFERENT, server.consistency)
    }

    @Test
    fun `should carry the project name into normalized sources`() {
        val server = service.normalize(
            listOf(raw(agentId = "claude", configPath = "/project/.mcp.json", projectName = "LlmBrains")),
        ).single()

        assertEquals("LlmBrains", server.sources.single().projectName)
    }

    @Test
    fun `should preserve healthy provider results when another provider fails`() {
        val failingProvider = object : McpProvider {
            override val agentId: String = "broken"

            override fun discoverGlobal(): List<RawMcpServer> = error("fixture failure")

            override fun discoverProject(project: DiscoveredProject) = emptyList<RawMcpServer>()
        }
        val healthyProvider = object : McpProvider {
            override val agentId: String = "claude"

            override fun discoverGlobal(): List<RawMcpServer> =
                listOf(raw(agentId = agentId, configPath = "/claude.json"))

            override fun discoverProject(project: DiscoveredProject) = emptyList<RawMcpServer>()
        }

        val servers = McpDiscoveryService(listOf(failingProvider, healthyProvider)).discoverGlobal()

        assertEquals(listOf("playwright"), servers.map { it.name })
    }

    private fun raw(
        agentId: String,
        configPath: String,
        name: String = "playwright",
        transport: McpTransport = McpTransport.STDIO,
        command: String? = "npx",
        args: List<String> = listOf("-y", "@playwright/mcp"),
        url: String? = null,
        environmentVariableNames: Set<String> = emptySet(),
        scope: McpScope = McpScope.GLOBAL,
        projectName: String? = null,
        environmentFile: String? = null,
        privateConfigurationFingerprint: String? = null,
    ) = RawMcpServer(
        agentId = agentId,
        name = name,
        transport = transport,
        command = command,
        args = args,
        url = url,
        environmentVariableNames = environmentVariableNames,
        configPath = configPath,
        scope = scope,
        projectName = projectName,
        environmentFile = environmentFile,
        privateConfigurationFingerprint = privateConfigurationFingerprint,
    )
}
