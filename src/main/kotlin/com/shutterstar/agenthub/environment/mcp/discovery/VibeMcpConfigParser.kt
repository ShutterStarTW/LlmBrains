package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope

/**
 * Mistral Vibe declares MCP servers as an array of tables in `config.toml`:
 *
 * ```
 * [[mcp_servers]]
 * name = "files"
 * transport = "stdio"          # or "http" / "streamable-http"
 * command = "npx"              # a string or a list
 * args = ["-y", "@scope/pkg"]
 * env = { TOKEN = "..." }      # or an `[mcp_servers.env]` sub-table
 * # http: url = "...", and `[mcp_servers.auth]` with `headers` / `api_key_env`
 * ```
 *
 * The Codex flavour of the TOML reader keys values by table path and so cannot tell two `[[mcp_servers]]` entries apart.
 * This splits the file into one block per entry (an entry ends at the next header that is not one of its own
 * `mcp_servers.*` sub-tables), reads each block with the shared value reader and maps it onto the per-name layout the
 * shared server parser expects. Only names of environment variables are kept, never their values.
 */
internal object VibeMcpConfigParser {
    private const val TABLE = "mcp_servers"
    private val HEADER = Regex("""^\[{1,2}\s*[A-Za-z0-9_.\-"' ]+\s*\]{1,2}$""")
    private val AUTH_PATH = listOf(TABLE, "auth")

    fun parse(
        content: String,
        configPath: String,
        scope: McpScope,
        projectName: String? = null,
        agentId: String = "vibe",
    ): List<RawMcpServer>? = runCatching {
        blocks(content).mapNotNull { block ->
            runCatching { parseBlock(block, configPath, scope, projectName, agentId) }.getOrNull()
        }
    }.getOrNull()

    private fun blocks(content: String): List<String> {
        val blocks = mutableListOf<StringBuilder>()
        var current: StringBuilder? = null
        content.lineSequence().forEach { line ->
            val trimmed = stripComment(line).trim()
            val isHeader = trimmed.isNotEmpty() && HEADER.matches(trimmed)
            when {
                isHeader && trimmed.startsWith("[[") -> {
                    val name = trimmed.removePrefix("[[").removeSuffix("]]").trim()
                    current = if (name == TABLE) StringBuilder().also(blocks::add) else null
                }
                isHeader -> {
                    val name = trimmed.removePrefix("[").removeSuffix("]").trim()
                    if (name != TABLE && !name.startsWith("$TABLE.")) current = null
                }
            }
            current?.append(line)?.append('\n')
        }
        return blocks.map(StringBuilder::toString)
    }

    private fun parseBlock(
        block: String,
        configPath: String,
        scope: McpScope,
        projectName: String?,
        agentId: String,
    ): RawMcpServer? {
        val values = CodexMcpConfigParser.parseValues(block)
        val name = values[listOf(TABLE, "name")]?.let(CodexMcpConfigParser::parseString)?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val prefix = listOf(TABLE, name)
        val remapped = linkedMapOf<List<String>, String>()
        values.forEach { (path, value) -> if (path.first() == TABLE && path.size >= 2) remapped[prefix + path.drop(1)] = value }
        // `command` may be a list: the first element is the executable, the rest lead the arguments.
        remapped[prefix + "command"]?.takeIf { it.trimStart().startsWith("[") }?.let { raw ->
            val parts = CodexMcpConfigParser.parseStringArray(raw)
            remapped[prefix + "command"] = parts.firstOrNull()?.let(::literal) ?: "\"\""
            val extra = parts.drop(1) + (remapped[prefix + "args"]?.let(CodexMcpConfigParser::parseStringArray).orEmpty())
            remapped[prefix + "args"] = extra.joinToString(prefix = "[", postfix = "]", transform = ::literal)
        }
        val server = CodexMcpConfigParser.parseServer(name, remapped, configPath, scope, projectName, agentId) ?: return null
        val tokenVariables = listOf(prefix + "api_key_env", prefix + AUTH_PATH.drop(1) + "api_key_env")
            .mapNotNull { remapped[it]?.let(CodexMcpConfigParser::parseString) }
        val authHeaders = authHeaderVariables(remapped, prefix)
        return server.copy(
            environmentVariableNames = McpEnvironmentVariables.normalized(server.environmentVariableNames + tokenVariables + authHeaders),
        )
    }

    /** Env references (`${NAME}` / `$NAME`) inside the header values of the `[mcp_servers.auth]` table. */
    private fun authHeaderVariables(values: Map<List<String>, String>, prefix: List<String>): Set<String> {
        val headerValues = values.entries
            .filter { (path, _) -> path.size == prefix.size + 3 && path.take(prefix.size + 2) == prefix + listOf("auth", "headers") }
            .mapNotNull { (_, value) -> CodexMcpConfigParser.parseString(value) }
        return McpEnvironmentVariables.collectFrom(*headerValues.toTypedArray())
    }

    private fun literal(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun stripComment(line: String): String {
        var quote: Char? = null
        line.forEachIndexed { index, character ->
            if (quote != null && character == quote) quote = null
            else if (quote == null && (character == '"' || character == '\'')) quote = character
            else if (quote == null && character == '#') return line.substring(0, index)
        }
        return line
    }
}
