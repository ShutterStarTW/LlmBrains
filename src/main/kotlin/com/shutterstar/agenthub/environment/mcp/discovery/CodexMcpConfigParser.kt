package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport

internal object CodexMcpConfigParser {
    fun parse(
        content: String,
        configPath: String,
        scope: McpScope,
        projectName: String? = null,
        agentId: String = AGENT_ID,
    ): List<RawMcpServer>? = runCatching {
        val values = parseValues(content)
        val serverNames = values.keys
            .filter { it.size >= 3 && it.first() == MCP_SERVERS_TABLE }
            .map { it[1] }
            .distinct()
            .sorted()

        serverNames.mapNotNull { serverName ->
            runCatching { parseServer(serverName, values, configPath, scope, projectName, agentId) }.getOrNull()
        }
    }.getOrNull()

    private fun parseServer(
        serverName: String,
        values: Map<List<String>, String>,
        configPath: String,
        scope: McpScope,
        projectName: String? = null,
        agentId: String = AGENT_ID,
    ): RawMcpServer? {
        if (serverName.isBlank()) return null
        val prefix = listOf(MCP_SERVERS_TABLE, serverName)
        val rawCommand = values[prefix + COMMAND_FIELD]?.let(::parseString)?.trim()?.takeIf(String::isNotEmpty)
        val command = McpSecretSanitizer.sanitizeCommand(rawCommand)
        val rawUrl = values[prefix + URL_FIELD]?.let(::parseString)?.trim()?.takeIf(String::isNotEmpty)
        val url = McpSecretSanitizer.sanitizeUrl(rawUrl)
        val rawArgs = values[prefix + ARGS_FIELD]?.let(::parseStringArray).orEmpty()
        val args = McpSecretSanitizer.sanitizeArguments(rawArgs)
        val transport = when {
            url != null && command == null -> McpTransport.HTTP
            command != null && url == null -> McpTransport.STDIO
            else -> McpTransport.UNKNOWN
        }
        if (command == null && url == null) return null

        val environmentVariables = linkedSetOf<String>()
        values.keys
            .filter { it.size == 4 && it.take(3) == prefix + ENV_FIELD }
            .mapTo(environmentVariables) { it.last() }
        values[prefix + ENV_FIELD]
            ?.let(::parseInlineTable)
            ?.keys
            ?.let(environmentVariables::addAll)
        values[prefix + ENV_VARS_FIELD]
            ?.let(::parseEnvironmentVariableArray)
            ?.let(environmentVariables::addAll)
        values[prefix + BEARER_TOKEN_ENV_FIELD]
            ?.let(::parseString)
            ?.let(environmentVariables::add)
        values[prefix + ENV_HTTP_HEADERS_FIELD]
            ?.let(::parseInlineTable)
            ?.values
            ?.mapNotNull(::parseString)
            ?.let(environmentVariables::addAll)
        values.entries
            .filter { (path, _) -> path.size == 4 && path.take(3) == prefix + ENV_HTTP_HEADERS_FIELD }
            .mapNotNullTo(environmentVariables) { (_, value) -> parseString(value) }
        collectHeaderEnvironmentVariables(values, prefix, environmentVariables)
        environmentVariables += McpEnvironmentVariables.collectFrom(rawCommand, rawUrl, *rawArgs.toTypedArray())

        return RawMcpServer(
            agentId = agentId,
            name = serverName,
            transport = transport,
            command = command,
            args = args,
            url = url,
            environmentVariableNames = McpEnvironmentVariables.normalized(environmentVariables),
            configPath = configPath,
            scope = scope,
            projectName = projectName,
        )
    }

    private fun collectHeaderEnvironmentVariables(
        values: Map<List<String>, String>,
        prefix: List<String>,
        environmentVariables: MutableSet<String>,
    ) {
        val headerValues = mutableListOf<String>()
        values[prefix + HEADERS_FIELD]
            ?.let(::parseInlineTable)
            ?.values
            ?.mapNotNull(::parseString)
            ?.let(headerValues::addAll)
        values.entries
            .filter { (path, _) -> path.size == 4 && path.take(3) == prefix + HEADERS_FIELD }
            .mapNotNullTo(headerValues) { (_, value) -> parseString(value) }
        environmentVariables += McpEnvironmentVariables.collectFrom(*headerValues.toTypedArray())
    }

    private fun parseValues(content: String): Map<List<String>, String> {
        val values = linkedMapOf<List<String>, String>()
        var currentTable = emptyList<String>()
        statements(content).forEach { statement ->
            if (statement.startsWith('[')) {
                require(statement.endsWith(']'))
                val arrayTable = statement.startsWith("[[") && statement.endsWith("]]")
                val startIndex = if (arrayTable) 2 else 1
                val endIndex = statement.length - if (arrayTable) 2 else 1
                currentTable = parseDottedPath(statement.substring(startIndex, endIndex))
                return@forEach
            }

            val assignmentIndex = findTopLevel(statement, '=')
            require(assignmentIndex > 0)
            val keyPath = parseDottedPath(statement.substring(0, assignmentIndex))
            val value = statement.substring(assignmentIndex + 1).trim()
            require(value.isNotEmpty())
            values[currentTable + keyPath] = value
        }
        return values
    }

    private fun statements(content: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        content.lineSequence().forEach { rawLine ->
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) return@forEach
            if (current.isNotEmpty()) current.append(' ')
            current.append(line)
            if (isBalanced(current)) {
                statements += current.toString()
                current.clear()
            }
        }
        require(current.isEmpty())
        return statements
    }

    private fun stripComment(line: String): String {
        var quote: Char? = null
        var escaped = false
        line.forEachIndexed { index, character ->
            if (escaped) {
                escaped = false
            } else if (quote == '"' && character == '\\') {
                escaped = true
            } else if (quote != null && character == quote) {
                quote = null
            } else if (quote == null && (character == '"' || character == '\'')) {
                quote = character
            } else if (quote == null && character == '#') {
                return line.substring(0, index)
            }
        }
        return line
    }

    private fun isBalanced(value: CharSequence): Boolean {
        var quote: Char? = null
        var escaped = false
        var squareDepth = 0
        var braceDepth = 0
        value.forEach { character ->
            if (escaped) {
                escaped = false
            } else if (quote == '"' && character == '\\') {
                escaped = true
            } else if (quote != null && character == quote) {
                quote = null
            } else if (quote == null && (character == '"' || character == '\'')) {
                quote = character
            } else if (quote == null) {
                when (character) {
                    '[' -> squareDepth++
                    ']' -> squareDepth--
                    '{' -> braceDepth++
                    '}' -> braceDepth--
                }
            }
        }
        return quote == null && squareDepth == 0 && braceDepth == 0
    }

    private fun parseDottedPath(rawPath: String): List<String> =
        splitTopLevel(rawPath, '.').map { segment ->
            val value = segment.trim()
            require(value.isNotEmpty())
            parseString(value) ?: value
        }

    private fun parseString(rawValue: String): String? {
        val value = rawValue.trim()
        if (value.length < 2) return null
        if (value.first() == '\'' && value.last() == '\'') {
            return value.substring(1, value.lastIndex)
        }
        if (value.first() != '"' || value.last() != '"') return null

        val result = StringBuilder()
        var index = 1
        while (index < value.lastIndex) {
            val character = value[index++]
            if (character != '\\') {
                result.append(character)
                continue
            }
            require(index < value.lastIndex)
            when (val escaped = value[index++]) {
                '"', '\\' -> result.append(escaped)
                'b' -> result.append('\b')
                't' -> result.append('\t')
                'n' -> result.append('\n')
                'f' -> result.append('\u000C')
                'r' -> result.append('\r')
                'u' -> {
                    require(index + 4 <= value.lastIndex)
                    result.append(value.substring(index, index + 4).toInt(16).toChar())
                    index += 4
                }
                else -> error("Unsupported TOML escape")
            }
        }
        return result.toString()
    }

    private fun parseStringArray(rawValue: String): List<String> =
        parseArrayElements(rawValue).mapNotNull(::parseString)

    private fun parseEnvironmentVariableArray(rawValue: String): Set<String> =
        parseArrayElements(rawValue).mapNotNullTo(linkedSetOf()) { element ->
            parseString(element) ?: parseInlineTable(element)[NAME_FIELD]?.let(::parseString)
        }

    private fun parseArrayElements(rawValue: String): List<String> {
        val value = rawValue.trim()
        require(value.startsWith('[') && value.endsWith(']'))
        val content = value.substring(1, value.lastIndex).trim()
        if (content.isEmpty()) return emptyList()
        return splitTopLevel(content, ',').map(String::trim).filter(String::isNotEmpty)
    }

    private fun parseInlineTable(rawValue: String): Map<String, String> {
        val value = rawValue.trim()
        require(value.startsWith('{') && value.endsWith('}'))
        val content = value.substring(1, value.lastIndex).trim()
        if (content.isEmpty()) return emptyMap()
        return splitTopLevel(content, ',').associate { assignment ->
            val separator = findTopLevel(assignment, '=')
            require(separator > 0)
            val key = parseDottedPath(assignment.substring(0, separator)).single()
            key to assignment.substring(separator + 1).trim()
        }
    }

    private fun splitTopLevel(value: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        var quote: Char? = null
        var escaped = false
        var squareDepth = 0
        var braceDepth = 0
        var partStart = 0
        value.forEachIndexed { index, character ->
            if (escaped) {
                escaped = false
            } else if (quote == '"' && character == '\\') {
                escaped = true
            } else if (quote != null && character == quote) {
                quote = null
            } else if (quote == null && (character == '"' || character == '\'')) {
                quote = character
            } else if (quote == null) {
                when (character) {
                    '[' -> squareDepth++
                    ']' -> squareDepth--
                    '{' -> braceDepth++
                    '}' -> braceDepth--
                    delimiter -> if (squareDepth == 0 && braceDepth == 0) {
                        parts += value.substring(partStart, index)
                        partStart = index + 1
                    }
                }
            }
        }
        require(quote == null && squareDepth == 0 && braceDepth == 0)
        parts += value.substring(partStart)
        return parts
    }

    private fun findTopLevel(value: String, target: Char): Int {
        var quote: Char? = null
        var escaped = false
        var squareDepth = 0
        var braceDepth = 0
        value.forEachIndexed { index, character ->
            if (escaped) {
                escaped = false
            } else if (quote == '"' && character == '\\') {
                escaped = true
            } else if (quote != null && character == quote) {
                quote = null
            } else if (quote == null && (character == '"' || character == '\'')) {
                quote = character
            } else if (quote == null) {
                if (character == target && squareDepth == 0 && braceDepth == 0) return index
                when (character) {
                    '[' -> squareDepth++
                    ']' -> squareDepth--
                    '{' -> braceDepth++
                    '}' -> braceDepth--
                }
            }
        }
        return -1
    }

    private const val AGENT_ID = "codex"
    private const val MCP_SERVERS_TABLE = "mcp_servers"
    private const val COMMAND_FIELD = "command"
    private const val ARGS_FIELD = "args"
    private const val URL_FIELD = "url"
    private const val ENV_FIELD = "env"
    private const val ENV_VARS_FIELD = "env_vars"
    private const val BEARER_TOKEN_ENV_FIELD = "bearer_token_env_var"
    private const val ENV_HTTP_HEADERS_FIELD = "env_http_headers"
    private const val HEADERS_FIELD = "headers"
    private const val NAME_FIELD = "name"
}
