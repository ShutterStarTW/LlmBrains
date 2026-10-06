package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import java.util.logging.Logger
import com.shutterstar.agenthub.AgentRuntime

/**
 * Freebuff MCP servers (Codebuff source `sdk/src/agents/load-mcp-config.ts`, `cli/src/utils/agent-dir-trust.ts`): the
 * `mcpServers` map of `mcp.json` in `<cwd>/.agents`, `<cwd>/../.agents` (a monorepo root) and `~/.agents`. A repository's
 * `.agents` directory is only loaded after the user trusted it (or with `--trust-agents`); the list shows what the files
 * declare, not what is currently trusted. Servers are `stdio` (`command`, `args`, `env`) or `http`/`sse` (`url`, `headers`).
 */
class FreebuffMcpProvider(
    private val homeDirectory: Path = AgentRuntime.userHome(),
) : McpProvider {
    override val agentId: String = AGENT_ID

    override fun discoverGlobal(): List<RawMcpServer> =
        discoverConfig(homeDirectory.resolve(AGENTS_DIRECTORY).resolve(MCP_FILE), McpScope.GLOBAL)

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val root = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        // The parent directory is read too (a monorepo root), unless that is the home directory, whose `.agents` is the global one.
        val home = homeDirectory.toAbsolutePath().normalize()
        val directories = listOfNotNull(root, root.parent?.takeUnless { it.toAbsolutePath().normalize() == home })
        return directories.flatMap { directory ->
            discoverConfig(directory.resolve(AGENTS_DIRECTORY).resolve(MCP_FILE), McpScope.PROJECT, project.name)
        }
    }

    private fun discoverConfig(configPath: Path, scope: McpScope, projectName: String? = null): List<RawMcpServer> {
        val root = McpConfigRootReader.read(configPath, "Freebuff", LOG) ?: return emptyList()
        return JsonMcpServerSupport.parseServers(
            agentId = agentId,
            container = root.fields[MCP_SERVERS_FIELD],
            configPath = configPath,
            scope = scope,
            projectName = projectName,
        )
    }

    private companion object {
        const val AGENT_ID = "freebuff"
        const val AGENTS_DIRECTORY = ".agents"
        const val MCP_FILE = "mcp.json"
        const val MCP_SERVERS_FIELD = "mcpServers"
        val LOG: Logger = Logger.getLogger(FreebuffMcpProvider::class.java.name)
    }
}
