package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Junie CLI MCP servers (docs: junie.jetbrains.com/docs/junie-cli-mcp-configuration.html): the `mcpServers` map of
 * `mcp/mcp.json` in the junie home (`~/.junie`, or `JUNIE_HOME`) and of `<project>/.junie/mcp/mcp.json`. Servers from
 * `JUNIE_MCP_LOCATIONS`, the `mcp-locations` key of `config.json` and extension-provided servers are not modelled.
 */
class JunieMcpProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : McpProvider {
    override val agentId: String = AGENT_ID
    private val junieHome = EnvHomeDirectorySupport.resolveGuarded("JUNIE_HOME", homeDirectory, JUNIE_DIRECTORY)

    override fun discoverGlobal(): List<RawMcpServer> = discoverConfig(junieHome.resolve(MCP_DIRECTORY).resolve(MCP_FILE), McpScope.GLOBAL)

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val root = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return discoverConfig(root.resolve(JUNIE_DIRECTORY).resolve(MCP_DIRECTORY).resolve(MCP_FILE), McpScope.PROJECT, project.name)
    }

    private fun discoverConfig(configPath: Path, scope: McpScope, projectName: String? = null): List<RawMcpServer> {
        val root = McpConfigRootReader.read(configPath, "Junie", LOG) ?: return emptyList()
        return JsonMcpServerSupport.parseServers(
            agentId = agentId,
            container = root.fields[MCP_SERVERS_FIELD],
            configPath = configPath,
            scope = scope,
            projectName = projectName,
        )
    }

    private companion object {
        const val AGENT_ID = "junie"
        const val JUNIE_DIRECTORY = ".junie"
        const val MCP_DIRECTORY = "mcp"
        const val MCP_FILE = "mcp.json"
        const val MCP_SERVERS_FIELD = "mcpServers"
        val LOG: Logger = Logger.getLogger(JunieMcpProvider::class.java.name)
    }
}
