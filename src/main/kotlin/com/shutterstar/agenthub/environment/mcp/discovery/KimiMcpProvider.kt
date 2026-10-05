package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Kimi Code CLI MCP servers (docs: `customization/mcp.md`): the `mcpServers` map of `mcp.json` in the data root
 * (`~/.kimi-code`, or `KIMI_CODE_HOME`) and of the project's `.kimi-code/mcp.json`. Plugin-provided servers are not modelled.
 */
class KimiMcpProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : McpProvider {
    override val agentId: String = AGENT_ID
    private val dataDirectory = EnvHomeDirectorySupport.resolveGuarded("KIMI_CODE_HOME", homeDirectory, DATA_DIRECTORY)

    override fun discoverGlobal(): List<RawMcpServer> = discoverConfig(dataDirectory.resolve(MCP_FILE), McpScope.GLOBAL)

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val root = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return discoverConfig(root.resolve(DATA_DIRECTORY).resolve(MCP_FILE), McpScope.PROJECT, project.name)
    }

    private fun discoverConfig(configPath: Path, scope: McpScope, projectName: String? = null): List<RawMcpServer> {
        val root = McpConfigRootReader.read(configPath, "Kimi Code", LOG) ?: return emptyList()
        return JsonMcpServerSupport.parseServers(
            agentId = agentId,
            container = root.fields[MCP_SERVERS_FIELD],
            configPath = configPath,
            scope = scope,
            projectName = projectName,
        )
    }

    private companion object {
        const val AGENT_ID = "kimi"
        const val DATA_DIRECTORY = ".kimi-code"
        const val MCP_FILE = "mcp.json"
        const val MCP_SERVERS_FIELD = "mcpServers"
        val LOG: Logger = Logger.getLogger(KimiMcpProvider::class.java.name)
    }
}
