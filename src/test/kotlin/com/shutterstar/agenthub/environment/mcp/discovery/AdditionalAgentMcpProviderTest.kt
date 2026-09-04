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

class AdditionalAgentMcpProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should discover Cline global and project MCP settings`() {
        write(
            temporaryDirectory.resolve(".cline/data/settings/cline_mcp_settings.json"),
            stdioConfig("global"),
        )
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("cline-project"))
        write(projectRoot.resolve(".cline/mcp.json"), httpConfig("project", "url"))

        val provider = ClineMcpProvider(temporaryDirectory)
        assertSafeStdio(provider.discoverGlobal().single(), "cline", McpScope.GLOBAL)
        assertSafeHttp(provider.discoverProject(project(projectRoot)).single(), "cline", McpScope.PROJECT, "project")
    }

    @Test
    fun `should discover Kiro global and project MCP settings`() {
        write(temporaryDirectory.resolve(".kiro/settings/mcp.json"), stdioConfig("global"))
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("kiro-project"))
        write(projectRoot.resolve(".kiro/settings/mcp.json"), httpConfig("project", "url"))

        val provider = KiroMcpProvider(temporaryDirectory)
        assertSafeStdio(provider.discoverGlobal().single(), "kiro", McpScope.GLOBAL)
        assertSafeHttp(provider.discoverProject(project(projectRoot)).single(), "kiro", McpScope.PROJECT, "project")
    }

    @Test
    fun `should recognize Qwen httpUrl and ignore malformed settings`() {
        write(temporaryDirectory.resolve(".qwen/settings.json"), "{ malformed")
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("qwen-project"))
        write(
            projectRoot.resolve(".qwen/settings.json"),
            """{"mcpServers":{"project":{"httpUrl":"https://example.test/mcp?token=secret"},"legacy":{"url":"https://example.test/sse"}}}""",
        )

        val provider = QwenMcpProvider(temporaryDirectory)
        assertTrue(provider.discoverGlobal().isEmpty())
        val projectServers = provider.discoverProject(project(projectRoot)).associateBy { it.name }
        assertSafeHttp(projectServers.getValue("project"), "qwen", McpScope.PROJECT, "project")
        assertEquals(McpTransport.SSE, projectServers.getValue("legacy").transport)
    }

    private fun assertSafeStdio(
        server: RawMcpServer,
        agentId: String,
        scope: McpScope,
        expectedProjectName: String? = null,
    ) {
        assertEquals(agentId, server.agentId)
        assertEquals(scope, server.scope)
        assertEquals(McpTransport.STDIO, server.transport)
        assertEquals(setOf("API_KEY"), server.environmentVariableNames)
        assertFalse(server.args.any { it.contains("secret") })
        assertEquals(expectedProjectName, server.projectName)
    }

    private fun assertSafeHttp(
        server: RawMcpServer,
        agentId: String,
        scope: McpScope,
        expectedProjectName: String? = null,
    ) {
        assertEquals(agentId, server.agentId)
        assertEquals(scope, server.scope)
        assertEquals(McpTransport.HTTP, server.transport)
        assertFalse(server.url.orEmpty().contains("secret"))
        assertEquals(expectedProjectName, server.projectName)
    }

    private fun stdioConfig(name: String): String =
        """{"mcpServers":{"$name":{"command":"npx","args":["--token","secret"],"env":{"API_KEY":"secret"}}}}"""

    private fun httpConfig(name: String, urlField: String): String =
        """{"mcpServers":{"$name":{"$urlField":"https://example.test/mcp?token=secret"}}}"""

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
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
