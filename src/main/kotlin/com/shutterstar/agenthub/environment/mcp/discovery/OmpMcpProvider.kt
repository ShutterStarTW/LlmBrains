package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.discovery.OmpHomeSupport
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import java.util.logging.Logger
import com.shutterstar.agenthub.AgentRuntime

/**
 * Oh My Pi MCP servers (docs: `mcp-config.md`): the `mcpServers` map of `mcp.json` / `.mcp.json` in the native agent
 * directory (`~/.omp/agent`) and in the project's `.omp/`, plus the portable root `mcp.json` / `.mcp.json`. The configs OMP
 * imports from other tools (`.claude`, `.cursor`, `.vscode`, …) belong to those agents' providers and are not repeated here.
 */
class OmpMcpProvider(
    homeDirectory: Path = AgentRuntime.userHome(),
) : McpProvider {
    override val agentId: String = AGENT_ID
    private val agentDirectory = OmpHomeSupport.agentDirectory(homeDirectory)

    override fun discoverGlobal(): List<RawMcpServer> =
        CONFIG_FILES.flatMap { discoverConfig(agentDirectory.resolve(it), McpScope.GLOBAL) }

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val root = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return (CONFIG_FILES.map { root.resolve(PROJECT_DIRECTORY).resolve(it) } + CONFIG_FILES.map(root::resolve))
            .flatMap { discoverConfig(it, McpScope.PROJECT, project.name) }
    }

    private fun discoverConfig(configPath: Path, scope: McpScope, projectName: String? = null): List<RawMcpServer> {
        val root = McpConfigRootReader.read(configPath, "Oh My Pi", LOG) ?: return emptyList()
        return JsonMcpServerSupport.parseServers(
            agentId = agentId,
            container = root.fields[MCP_SERVERS_FIELD],
            configPath = configPath,
            scope = scope,
            projectName = projectName,
        )
    }

    private companion object {
        const val AGENT_ID = "omp"
        const val PROJECT_DIRECTORY = ".omp"
        const val MCP_SERVERS_FIELD = "mcpServers"
        val CONFIG_FILES = listOf("mcp.json", ".mcp.json")
        val LOG: Logger = Logger.getLogger(OmpMcpProvider::class.java.name)
    }
}
