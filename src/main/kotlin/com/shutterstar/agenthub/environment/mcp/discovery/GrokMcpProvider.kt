package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import java.util.logging.Logger

class GrokMcpProvider(
    private val grokDirectory: Path = defaultGrokDirectory(),
) : McpProvider {
    override val agentId: String = AGENT_ID

    override fun discoverGlobal(): List<RawMcpServer> =
        parseConfig(grokDirectory.resolve(CONFIG_FILE), McpScope.GLOBAL)

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return parseConfig(projectRoot.resolve(GROK_DIRECTORY).resolve(CONFIG_FILE), McpScope.PROJECT, project.name)
    }

    private fun parseConfig(configPath: Path, scope: McpScope, projectName: String? = null): List<RawMcpServer> =
        TomlMcpConfigReader.read(configPath, "Grok", AGENT_ID, scope, projectName, LOG)

    private companion object {
        const val AGENT_ID = "grok"
        const val GROK_DIRECTORY = ".grok"
        const val CONFIG_FILE = "config.toml"
        val LOG: Logger = Logger.getLogger(GrokMcpProvider::class.java.name)

        fun defaultGrokDirectory(): Path = EnvHomeDirectorySupport.resolve("GROK_HOME", GROK_DIRECTORY)
    }
}
