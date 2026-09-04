package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.discovery.CursorLocalPlugin
import com.shutterstar.agenthub.environment.discovery.CursorPluginDiscoverySupport
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import java.util.logging.Logger

class CursorMcpProvider(
    private val homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : McpProvider {
    override val agentId: String = AGENT_ID
    private val globalConfig = homeDirectory.resolve(CURSOR_DIRECTORY).resolve(MCP_FILE)

    override fun discoverGlobal(): List<RawMcpServer> =
        (discoverConfig(globalConfig, McpScope.GLOBAL) + discoverPluginServers())
            .distinctBy { listOf(it.configPath, it.name, it.scope.name) }

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val root = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return discoverConfig(root.resolve(CURSOR_DIRECTORY).resolve(MCP_FILE), McpScope.PROJECT, project.name)
    }

    private fun discoverConfig(
        configPath: Path,
        scope: McpScope,
        projectName: String? = null,
        maximumServers: Int = MAXIMUM_CONFIG_SERVERS,
    ): List<RawMcpServer> {
        val root = readRoot(configPath) ?: return emptyList()
        return JsonMcpServerSupport.parseServers(
            agentId = agentId,
            container = root.fields[MCP_SERVERS_FIELD],
            configPath = configPath,
            scope = scope,
            projectName = projectName,
            maximumServers = maximumServers,
        )
    }

    private fun readRoot(configPath: Path): JsonObject? =
        McpConfigRootReader.read(configPath, "Cursor", LOG)

    private fun discoverPluginServers(): List<RawMcpServer> {
        val servers = mutableListOf<RawMcpServer>()
        val componentBudget = ComponentBudget(MAXIMUM_PLUGIN_COMPONENTS)
        for (plugin in CursorPluginDiscoverySupport.discover(homeDirectory)) {
            for (server in pluginServers(plugin, componentBudget)) {
                if (servers.size >= MAXIMUM_PLUGIN_MCP_SERVERS) return servers
                servers += server
            }
            if (!componentBudget.hasRemaining()) return servers
        }
        return servers
    }

    private fun pluginServers(
        plugin: CursorLocalPlugin,
        componentBudget: ComponentBudget,
    ): Sequence<RawMcpServer> {
        if (!plugin.cursorPlugin) {
            if (!componentBudget.consume()) return emptySequence()
            return discoverConfig(
                plugin.root.resolve(MCP_FILE),
                McpScope.GLOBAL,
                maximumServers = MAXIMUM_PLUGIN_MCP_SERVERS,
            ).asSequence()
        }
        return when (val configured = plugin.manifest.fields[MCP_SERVERS_FIELD]) {
            null -> if (componentBudget.consume()) {
                discoverConfig(
                    plugin.root.resolve(MCP_FILE),
                    McpScope.GLOBAL,
                    maximumServers = MAXIMUM_PLUGIN_MCP_SERVERS,
                ).asSequence()
            } else {
                emptySequence()
            }
            is JsonString -> if (componentBudget.consume()) {
                discoverPluginConfig(plugin, configured.value).asSequence()
            } else {
                emptySequence()
            }
            is JsonObject -> if (componentBudget.consume()) {
                parsePluginContainer(plugin, configured).asSequence()
            } else {
                emptySequence()
            }
            is JsonArray -> configured.values.asSequence().takeWhile { componentBudget.consume() }.flatMap { value ->
                when (value) {
                    is JsonString -> discoverPluginConfig(plugin, value.value).asSequence()
                    is JsonObject -> parsePluginContainer(plugin, value).asSequence()
                    else -> emptySequence()
                }
            }
            else -> emptySequence()
        }
    }

    private fun discoverPluginConfig(plugin: CursorLocalPlugin, relativePath: String): List<RawMcpServer> {
        val path = CursorPluginDiscoverySupport.safeResolve(plugin.root, relativePath) ?: return emptyList()
        return discoverConfig(path, McpScope.GLOBAL, maximumServers = MAXIMUM_PLUGIN_MCP_SERVERS)
    }

    private fun parsePluginContainer(
        plugin: CursorLocalPlugin,
        container: JsonObject,
    ): List<RawMcpServer> = JsonMcpServerSupport.parseServers(
        agentId = agentId,
        container = container,
        configPath = plugin.manifestPath,
        scope = McpScope.GLOBAL,
        maximumServers = MAXIMUM_PLUGIN_MCP_SERVERS,
    )

    private companion object {
        const val AGENT_ID = "cursor"
        const val CURSOR_DIRECTORY = ".cursor"
        const val MCP_FILE = "mcp.json"
        const val MCP_SERVERS_FIELD = "mcpServers"
        const val MAXIMUM_CONFIG_SERVERS = 20_000
        const val MAXIMUM_PLUGIN_COMPONENTS = 512
        const val MAXIMUM_PLUGIN_MCP_SERVERS = 1_024
        val LOG: Logger = Logger.getLogger(CursorMcpProvider::class.java.name)
    }

    private class ComponentBudget(
        private var remaining: Int,
    ) {
        fun hasRemaining(): Boolean = remaining > 0

        fun consume(): Boolean {
            if (remaining <= 0) return false
            remaining--
            return true
        }
    }
}
