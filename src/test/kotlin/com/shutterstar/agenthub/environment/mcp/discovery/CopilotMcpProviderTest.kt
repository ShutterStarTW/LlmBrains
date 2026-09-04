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

class CopilotMcpProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should discover personal and both repository MCP formats`() {
        val copilotHome = Files.createDirectories(temporaryDirectory.resolve(".copilot"))
        Files.writeString(
            copilotHome.resolve("mcp-config.json"),
            """{"mcpServers":{"global":{"type":"local","command":"npx","env":{"TOKEN":"secret"}}}}""",
        )
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"))
        Files.writeString(
            projectRoot.resolve(".mcp.json"),
            """{"local":{"command":"node","args":["--api-key=secret"]}}""",
        )
        val github = Files.createDirectories(projectRoot.resolve(".github"))
        Files.writeString(
            github.resolve("mcp.json"),
            """{"mcpServers":{"shared":{"type":"http","url":"https://example.test/mcp?token=secret","headers":{"X-Key":"${'$'}{SHARED_KEY}"}}}}""",
        )

        val provider = CopilotMcpProvider(copilotHome)
        val global = provider.discoverGlobal().single()
        val project = provider.discoverProject(project(projectRoot)).associateBy { it.name }

        assertEquals(McpScope.GLOBAL, global.scope)
        assertEquals(setOf("TOKEN"), global.environmentVariableNames)
        assertEquals(null, global.projectName)
        assertEquals(setOf("local", "shared"), project.keys)
        assertEquals(McpTransport.STDIO, project.getValue("local").transport)
        assertFalse(project.getValue("local").args.any { it.contains("secret") })
        assertEquals(McpTransport.HTTP, project.getValue("shared").transport)
        assertEquals(setOf("SHARED_KEY"), project.getValue("shared").environmentVariableNames)
        assertFalse(project.getValue("shared").url.orEmpty().contains("secret"))
        assertTrue(project.values.all { it.projectName == "project" })
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
