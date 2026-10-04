package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import java.util.logging.Logger

class GrokMcpProvider(
    private val grokDirectory: Path = defaultGrokDirectory(),
    private val homeDirectory: Path = defaultCompatibilityHome(grokDirectory),
) : McpProvider {
    override val agentId: String = AGENT_ID

    override fun discoverGlobal(): List<RawMcpServer> =
        (parseConfig(grokDirectory.resolve(CONFIG_FILE), McpScope.GLOBAL) + compatibleGlobalServers())
            .distinctBy { it.configPath to it.name }

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return (
            parseConfig(projectRoot.resolve(GROK_DIRECTORY).resolve(CONFIG_FILE), McpScope.PROJECT, project.name) +
                compatibleProjectServers(project)
            ).distinctBy { it.configPath to it.name }
    }

    private fun compatibleGlobalServers(): List<RawMcpServer> =
        ClaudeMcpProvider(homeDirectory).discoverGlobal().map(::asGrokServer) +
            CursorMcpProvider(homeDirectory).discoverGlobal()
                .filter { it.configPath == homeDirectory.resolve(".cursor/mcp.json").toAbsolutePath().normalize().toString() }
                .map(::asGrokServer)

    private fun compatibleProjectServers(project: DiscoveredProject): List<RawMcpServer> =
        ClaudeMcpProvider(homeDirectory).discoverProject(project).map(::asGrokServer) +
            CursorMcpProvider(homeDirectory).discoverProject(project).map(::asGrokServer)

    private fun asGrokServer(server: RawMcpServer): RawMcpServer = server.copy(agentId = agentId)

    private fun parseConfig(configPath: Path, scope: McpScope, projectName: String? = null): List<RawMcpServer> =
        TomlMcpConfigReader.read(configPath, "Grok", AGENT_ID, scope, projectName, LOG)

    private companion object {
        const val AGENT_ID = "grok"
        const val GROK_DIRECTORY = ".grok"
        const val CONFIG_FILE = "config.toml"
        val LOG: Logger = Logger.getLogger(GrokMcpProvider::class.java.name)

        fun defaultGrokDirectory(): Path = EnvHomeDirectorySupport.resolve("GROK_HOME", GROK_DIRECTORY)

        fun defaultCompatibilityHome(grokDirectory: Path): Path {
            val userHome = Path.of(System.getProperty("user.home"))
            return if (grokDirectory == defaultGrokDirectory()) userHome else grokDirectory.parent ?: userHome
        }
    }
}
