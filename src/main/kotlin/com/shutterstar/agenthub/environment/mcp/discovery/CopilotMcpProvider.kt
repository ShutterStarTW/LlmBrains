package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import java.util.logging.Logger

class CopilotMcpProvider(
    private val copilotDirectory: Path = defaultCopilotDirectory(),
) : McpProvider {
    override val agentId: String = AGENT_ID

    override fun discoverGlobal(): List<RawMcpServer> = discoverConfig(
        copilotDirectory.resolve(GLOBAL_MCP_FILE),
        McpScope.GLOBAL,
    )

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return listOf(
            projectRoot.resolve(LOCAL_MCP_FILE),
            projectRoot.resolve(GITHUB_DIRECTORY).resolve(SHARED_MCP_FILE),
        ).flatMap { configPath -> discoverConfig(configPath, McpScope.PROJECT, project.name) }
    }

    private fun discoverConfig(
        configPath: Path,
        scope: McpScope,
        projectName: String? = null,
    ): List<RawMcpServer> {
        val root = readRoot(configPath) ?: return emptyList()
        val container = root.fields[MCP_SERVERS_FIELD] ?: root
        return JsonMcpServerSupport.parseServers(
            agentId = agentId,
            container = container,
            configPath = configPath,
            scope = scope,
            projectName = projectName,
        )
    }

    private fun readRoot(configPath: Path): JsonObject? = McpConfigRootReader.read(
        configPath,
        "Copilot",
        LOG,
        jsonc = true,
        existenceCheckFollowsLinks = false,
        ignoreBlankContent = true,
    )

    private companion object {
        const val AGENT_ID = "copilot"
        const val COPILOT_DIRECTORY = ".copilot"
        const val GITHUB_DIRECTORY = ".github"
        const val GLOBAL_MCP_FILE = "mcp-config.json"
        const val LOCAL_MCP_FILE = ".mcp.json"
        const val SHARED_MCP_FILE = "mcp.json"
        const val MCP_SERVERS_FIELD = "mcpServers"
        val LOG: Logger = Logger.getLogger(CopilotMcpProvider::class.java.name)

        fun defaultCopilotDirectory(): Path = EnvHomeDirectorySupport.resolve("COPILOT_HOME", COPILOT_DIRECTORY)
    }
}
