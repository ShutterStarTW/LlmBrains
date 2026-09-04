package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.discovery.AntigravityHomeSupport
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.logging.Logger

class AntigravityMcpProvider(
    private val homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : McpProvider {
    override val agentId: String = AGENT_ID

    override fun discoverGlobal(): List<RawMcpServer> {
        val directServers = globalConfigs().flatMap { configPath ->
            discoverConfig(configPath, McpScope.GLOBAL)
        }
        val pluginServers = globalPluginConfigs().flatMap { configPath ->
            discoverConfig(configPath, McpScope.GLOBAL)
        }
        return (directServers + pluginServers).distinctBy { it.name.lowercase() }
    }

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        val directServers = projectConfigs(projectRoot).flatMap { configPath ->
            discoverConfig(configPath, McpScope.PROJECT, project.name)
        }
        val pluginServers = projectPluginConfigs(projectRoot).flatMap { configPath ->
            discoverConfig(configPath, McpScope.PROJECT, project.name)
        }
        return (directServers + pluginServers).distinctBy { it.name.lowercase() }
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
        "Antigravity",
        LOG,
        jsonc = true,
        existenceCheckFollowsLinks = false,
        ignoreBlankContent = true,
    )

    private fun globalConfigs(): List<Path> {
        val gemini = homeDirectory.resolve(GEMINI_DIRECTORY)
        return listOfNotNull(
            gemini.resolve(CONFIG_DIRECTORY).resolve(MCP_CONFIG_FILE),
            gemini.resolve(CLI_DIRECTORY).resolve(MCP_CONFIG_FILE),
            gemini.resolve(MCP_CONFIG_FILE),
            gemini.resolve(ANTIGRAVITY_DIRECTORY).resolve(MCP_CONFIG_FILE),
            AntigravityHomeSupport.configuredHome()?.resolve(MCP_CONFIG_FILE),
            AntigravityHomeSupport.configuredHome()?.resolve(CONFIG_DIRECTORY)?.resolve(MCP_CONFIG_FILE),
        ).distinct()
    }

    private fun globalPluginConfigs(): List<Path> {
        val gemini = homeDirectory.resolve(GEMINI_DIRECTORY)
        val pluginDirs = listOfNotNull(
            gemini.resolve(PLUGINS_DIRECTORY),
            gemini.resolve(CONFIG_DIRECTORY).resolve(PLUGINS_DIRECTORY),
            gemini.resolve(CLI_DIRECTORY).resolve(PLUGINS_DIRECTORY),
            gemini.resolve(ANTIGRAVITY_DIRECTORY).resolve(PLUGINS_DIRECTORY),
            AntigravityHomeSupport.configuredHome()?.resolve(PLUGINS_DIRECTORY),
        ).distinct()
        return scanPluginsForMcp(pluginDirs)
    }

    private fun projectConfigs(projectRoot: Path): List<Path> = listOf(
        projectRoot.resolve(AGENTS_DIRECTORY).resolve(MCP_CONFIG_FILE),
        projectRoot.resolve(LEGACY_AGENT_DIRECTORY).resolve(MCP_CONFIG_FILE),
        projectRoot.resolve(ALT_AGENTS_DIRECTORY).resolve(MCP_CONFIG_FILE),
        projectRoot.resolve(ALT_AGENT_DIRECTORY).resolve(MCP_CONFIG_FILE),
        projectRoot.resolve(GEMINI_DIRECTORY).resolve(MCP_CONFIG_FILE),
        projectRoot.resolve(MCP_CONFIG_FILE),
    )

    private fun projectPluginConfigs(projectRoot: Path): List<Path> {
        val pluginDirs = listOf(
            projectRoot.resolve(AGENTS_DIRECTORY).resolve(PLUGINS_DIRECTORY),
            projectRoot.resolve(LEGACY_AGENT_DIRECTORY).resolve(PLUGINS_DIRECTORY),
            projectRoot.resolve(ALT_AGENTS_DIRECTORY).resolve(PLUGINS_DIRECTORY),
            projectRoot.resolve(ALT_AGENT_DIRECTORY).resolve(PLUGINS_DIRECTORY),
        )
        return scanPluginsForMcp(pluginDirs)
    }

    private fun scanPluginsForMcp(pluginDirs: List<Path>): List<Path> =
        AntigravityHomeSupport.forEachPluginDirectory(pluginDirs, MAX_PLUGIN_ENTRIES) { pluginDir ->
            val mcpFile = pluginDir.resolve(MCP_CONFIG_FILE)
            if (Files.isRegularFile(mcpFile, LinkOption.NOFOLLOW_LINKS)) listOf(mcpFile) else emptyList()
        }

    private companion object {
        const val AGENT_ID = "antigravity"
        const val GEMINI_DIRECTORY = ".gemini"
        const val CONFIG_DIRECTORY = "config"
        const val CLI_DIRECTORY = "antigravity-cli"
        const val ANTIGRAVITY_DIRECTORY = "antigravity"
        const val AGENTS_DIRECTORY = ".agents"
        const val LEGACY_AGENT_DIRECTORY = ".agent"
        const val ALT_AGENTS_DIRECTORY = "_agents"
        const val ALT_AGENT_DIRECTORY = "_agent"
        const val PLUGINS_DIRECTORY = "plugins"
        const val MCP_CONFIG_FILE = "mcp_config.json"
        const val MCP_SERVERS_FIELD = "mcpServers"
        const val MAX_PLUGIN_ENTRIES = 128
        val LOG: Logger = Logger.getLogger(AntigravityMcpProvider::class.java.name)
    }
}
