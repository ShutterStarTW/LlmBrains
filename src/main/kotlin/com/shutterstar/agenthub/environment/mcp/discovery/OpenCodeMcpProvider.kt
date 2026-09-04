package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.logging.Logger

class OpenCodeMcpProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : McpProvider {
    override val agentId: String = AGENT_ID
    private val globalDirectory = homeDirectory.resolve(".config").resolve(OPENCODE_DIRECTORY)

    override fun discoverGlobal(): List<RawMcpServer> =
        discoverConfig(findConfig(globalDirectory), McpScope.GLOBAL)

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val root = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return discoverConfig(findConfig(root), McpScope.PROJECT, project.name)
    }

    private fun discoverConfig(
        configPath: Path?,
        scope: McpScope,
        projectName: String? = null,
    ): List<RawMcpServer> {
        configPath ?: return emptyList()
        val root = readRoot(configPath) ?: return emptyList()
        val mcp = root.fields[MCP_FIELD] as? JsonObject ?: return emptyList()
        val container = mcp.fields[SERVERS_FIELD] ?: mcp
        return JsonMcpServerSupport.parseServers(
            agentId = agentId,
            container = container,
            configPath = configPath,
            scope = scope,
            projectName = projectName,
        )
    }

    private fun findConfig(directory: Path): Path? = listOf(JSON_FILE, JSONC_FILE)
        .map(directory::resolve)
        .firstOrNull { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }

    private fun readRoot(configPath: Path): JsonObject? = McpConfigRootReader.read(
        configPath,
        "OpenCode",
        LOG,
        jsonc = true,
        warnOnlyIfFileExists = false,
    )

    private companion object {
        const val AGENT_ID = "opencode"
        const val OPENCODE_DIRECTORY = "opencode"
        const val JSON_FILE = "opencode.json"
        const val JSONC_FILE = "opencode.jsonc"
        const val MCP_FIELD = "mcp"
        const val SERVERS_FIELD = "servers"
        val LOG: Logger = Logger.getLogger(OpenCodeMcpProvider::class.java.name)
    }
}
