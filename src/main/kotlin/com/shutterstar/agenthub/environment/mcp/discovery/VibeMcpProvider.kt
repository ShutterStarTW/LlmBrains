package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Mistral Vibe MCP servers (`vibe/core/config/vibe_schema.py`, `models.py`): the `[[mcp_servers]]` entries of the user
 * `config.toml` in the vibe home (`~/.vibe`, or `VIBE_HOME`) and of the project's `.vibe/config.toml` (which Vibe only
 * applies once the folder is trusted). Connector-provided servers and plugin servers are not modelled.
 */
class VibeMcpProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : McpProvider {
    override val agentId: String = AGENT_ID
    private val vibeHome = EnvHomeDirectorySupport.resolveGuarded("VIBE_HOME", homeDirectory, VIBE_DIRECTORY)

    override fun discoverGlobal(): List<RawMcpServer> = discoverConfig(vibeHome.resolve(CONFIG_FILE), McpScope.GLOBAL)

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val root = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return discoverConfig(root.resolve(VIBE_DIRECTORY).resolve(CONFIG_FILE), McpScope.PROJECT, project.name)
    }

    private fun discoverConfig(configPath: Path, scope: McpScope, projectName: String? = null): List<RawMcpServer> {
        val content = McpConfigFileReader.read(configPath)
        if (content == null) {
            if (Files.exists(configPath)) LOG.warning("[McpDiscovery] Mistral Vibe: unreadable or oversized config: $configPath")
            return emptyList()
        }
        val servers = VibeMcpConfigParser.parse(content, configPath.toAbsolutePath().normalize().toString(), scope, projectName, agentId)
        if (servers == null) LOG.warning("[McpDiscovery] Mistral Vibe: malformed config: $configPath")
        return servers.orEmpty()
    }

    private companion object {
        const val AGENT_ID = "vibe"
        const val VIBE_DIRECTORY = ".vibe"
        const val CONFIG_FILE = "config.toml"
        val LOG: Logger = Logger.getLogger(VibeMcpProvider::class.java.name)
    }
}
