package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale
import java.util.logging.Logger

class ClaudeMcpProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : McpProvider {
    override val agentId: String = AGENT_ID

    private val userConfig = homeDirectory.resolve(CLAUDE_CONFIG_FILE)

    override fun discoverGlobal(): List<RawMcpServer> {
        val root = readRoot(userConfig) ?: return emptyList()
        return parseServerContainer(
            container = root.fields[MCP_SERVERS_FIELD],
            configPath = userConfig,
            scope = McpScope.GLOBAL,
        )
    }

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        val sharedConfig = projectRoot.resolve(PROJECT_MCP_FILE)
        val sharedServers = readRoot(sharedConfig)?.let { root ->
            parseServerContainer(
                container = root.fields[MCP_SERVERS_FIELD],
                configPath = sharedConfig,
                scope = McpScope.PROJECT,
                projectName = project.name,
            )
        }.orEmpty()
        val localServers = discoverLocalProjectServers(projectRoot, project.name)
        return sharedServers + localServers
    }

    private fun discoverLocalProjectServers(projectRoot: Path, projectName: String): List<RawMcpServer> {
        val root = readRoot(userConfig) ?: return emptyList()
        val projects = root.fields[PROJECTS_FIELD] as? JsonObject ?: return emptyList()
        return projects.fields.entries.flatMap { (configuredPath, projectValue) ->
            if (!pathsMatch(configuredPath, projectRoot)) {
                return@flatMap emptyList()
            }
            val projectObject = projectValue as? JsonObject ?: return@flatMap emptyList()
            parseServerContainer(
                container = projectObject.fields[MCP_SERVERS_FIELD],
                configPath = userConfig,
                scope = McpScope.PROJECT,
                projectName = projectName,
            )
        }
    }

    private fun parseServerContainer(
        container: JsonValue?,
        configPath: Path,
        scope: McpScope,
        projectName: String? = null,
    ): List<RawMcpServer> {
        val servers = container as? JsonObject ?: return emptyList()
        return servers.fields.mapNotNull { (name, value) ->
            parseServer(name, value as? JsonObject ?: return@mapNotNull null, configPath, scope, projectName)
        }
    }

    private fun parseServer(
        name: String,
        server: JsonObject,
        configPath: Path,
        scope: McpScope,
        projectName: String? = null,
    ): RawMcpServer? {
        if (name.isBlank()) return null
        val type = server.string(TYPE_FIELD)?.trim()?.lowercase(Locale.ROOT)
        val rawCommand = server.string(COMMAND_FIELD)?.trim()?.takeIf(String::isNotEmpty)
        val command = McpSecretSanitizer.sanitizeCommand(rawCommand)
        val rawArgs = server.stringArray(ARGS_FIELD)
        val args = McpSecretSanitizer.sanitizeArguments(rawArgs)
        val rawUrl = server.string(URL_FIELD)?.trim()?.takeIf(String::isNotEmpty)
        val url = McpSecretSanitizer.sanitizeUrl(rawUrl)
        val transport = when (type) {
            null, "stdio" -> McpTransport.STDIO
            "http", "streamable-http" -> McpTransport.HTTP
            "sse" -> McpTransport.SSE
            else -> McpTransport.UNKNOWN
        }
        when (transport) {
            McpTransport.STDIO -> if (command == null) return null
            McpTransport.HTTP, McpTransport.SSE -> if (url == null) return null
            McpTransport.UNKNOWN -> if (command == null && url == null) return null
        }

        val environmentVariables = linkedSetOf<String>()
        val environment = server.fields[ENV_FIELD] as? JsonObject
        environment?.fields?.forEach { (key, value) ->
            environmentVariables += key
            environmentVariables += McpEnvironmentVariables.collectFrom((value as? JsonString)?.value)
        }
        val headers = server.fields[HEADERS_FIELD] as? JsonObject
        headers?.fields?.values?.forEach { value ->
            environmentVariables += McpEnvironmentVariables.collectFrom((value as? JsonString)?.value)
        }
        environmentVariables += McpEnvironmentVariables.collectFrom(rawCommand, rawUrl, *rawArgs.toTypedArray())

        return RawMcpServer(
            agentId = agentId,
            name = name.trim(),
            transport = transport,
            command = command,
            args = args,
            url = url,
            environmentVariableNames = McpEnvironmentVariables.normalized(environmentVariables),
            configPath = configPath.toAbsolutePath().normalize().toString(),
            scope = scope,
            projectName = projectName,
        )
    }

    private fun readRoot(configPath: Path): JsonObject? =
        McpConfigRootReader.read(configPath, "Claude", LOG)

    private fun pathsMatch(configuredPath: String, projectRoot: Path): Boolean {
        val normalizedConfigured = try {
            Path.of(configuredPath).toAbsolutePath().normalize().toString()
        } catch (_: InvalidPathException) {
            return false
        }
        val normalizedProject = projectRoot.toString()
        return if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            normalizedConfigured.equals(normalizedProject, ignoreCase = true)
        } else {
            normalizedConfigured == normalizedProject
        }
    }

    private fun JsonObject.string(field: String): String? =
        (fields[field] as? JsonString)?.value

    private fun JsonObject.stringArray(field: String): List<String> =
        ((fields[field] as? JsonArray)?.values ?: emptyList())
            .mapNotNull { (it as? JsonString)?.value }

    private companion object {
        const val AGENT_ID = "claude"
        const val CLAUDE_CONFIG_FILE = ".claude.json"
        const val PROJECT_MCP_FILE = ".mcp.json"
        const val PROJECTS_FIELD = "projects"
        const val MCP_SERVERS_FIELD = "mcpServers"
        const val TYPE_FIELD = "type"
        const val COMMAND_FIELD = "command"
        const val ARGS_FIELD = "args"
        const val URL_FIELD = "url"
        const val ENV_FIELD = "env"
        const val HEADERS_FIELD = "headers"
        val LOG: Logger = Logger.getLogger(ClaudeMcpProvider::class.java.name)
    }
}
