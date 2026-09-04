package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import java.util.logging.Logger

class CodexMcpProvider(
    private val codexDirectory: Path = defaultCodexDirectory(),
) : McpProvider {
    override val agentId: String = AGENT_ID

    override fun discoverGlobal(): List<RawMcpServer> =
        parseConfig(codexDirectory.resolve(CONFIG_FILE), McpScope.GLOBAL)

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return parseConfig(projectRoot.resolve(CODEX_DIRECTORY).resolve(CONFIG_FILE), McpScope.PROJECT, project.name)
    }

    private fun parseConfig(configPath: Path, scope: McpScope, projectName: String? = null): List<RawMcpServer> =
        TomlMcpConfigReader.read(configPath, "Codex", AGENT_ID, scope, projectName, LOG)

    private companion object {
        const val AGENT_ID = "codex"
        const val CODEX_DIRECTORY = ".codex"
        const val CONFIG_FILE = "config.toml"
        val LOG: Logger = Logger.getLogger(CodexMcpProvider::class.java.name)

        fun defaultCodexDirectory(): Path = EnvHomeDirectorySupport.resolve("CODEX_HOME", CODEX_DIRECTORY)
    }
}
