package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

internal object JsonMcpServerSupport {
    fun parseServers(
        agentId: String,
        container: JsonValue?,
        configPath: Path,
        scope: McpScope,
        urlImpliesSse: Boolean = false,
        projectName: String? = null,
        maximumServers: Int = MAXIMUM_SERVERS,
    ): List<RawMcpServer> {
        val servers = container as? JsonObject ?: return emptyList()
        return servers.fields.asSequence().take(maximumServers).mapNotNull { (name, value) ->
            parseServer(
                agentId,
                name,
                value as? JsonObject ?: return@mapNotNull null,
                configPath,
                scope,
                urlImpliesSse,
                projectName,
            )
        }.toList()
    }

    private fun parseServer(
        agentId: String,
        name: String,
        server: JsonObject,
        configPath: Path,
        scope: McpScope,
        urlImpliesSse: Boolean,
        projectName: String?,
    ): RawMcpServer? {
        if (name.isBlank()) return null
        val commandValue = server.fields[COMMAND_FIELD]
        val commandParts = (commandValue as? JsonArray)?.stringValues().orEmpty()
        val rawCommand = (commandValue as? JsonString)?.value?.trim()?.takeIf(String::isNotEmpty)
            ?: commandParts.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
        val rawArgs = if (commandParts.isNotEmpty()) commandParts.drop(1) else server.stringArray(ARGS_FIELD)
        val rawUrl = (server.string(HTTP_URL_FIELD) ?: server.string(URL_FIELD) ?: server.string(SERVER_URL_FIELD))
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val rawEnvironmentFile = server.string(ENV_FILE_FIELD)?.trim()?.takeIf(String::isNotEmpty)
        val type = server.string(TYPE_FIELD)?.trim()?.lowercase(Locale.ROOT)
        val transport = when (type) {
            "local", "stdio" -> McpTransport.STDIO
            "remote", "http", "streamable-http" -> McpTransport.HTTP
            "sse" -> McpTransport.SSE
            null -> when {
                rawCommand != null -> McpTransport.STDIO
                urlImpliesSse && server.string(HTTP_URL_FIELD) == null && server.string(URL_FIELD) != null -> McpTransport.SSE
                rawUrl != null -> McpTransport.HTTP
                else -> McpTransport.UNKNOWN
            }
            else -> McpTransport.UNKNOWN
        }
        when (transport) {
            McpTransport.STDIO -> if (rawCommand == null) return null
            McpTransport.HTTP, McpTransport.SSE -> if (rawUrl == null) return null
            McpTransport.UNKNOWN -> if (rawCommand == null && rawUrl == null) return null
        }

        val environmentVariableNames = linkedSetOf<String>()
        listOf(ENV_FIELD, ENVIRONMENT_FIELD).forEach { field ->
            (server.fields[field] as? JsonObject)?.fields?.forEach { (key, value) ->
                environmentVariableNames += key
                environmentVariableNames += McpEnvironmentVariables.collectFrom((value as? JsonString)?.value)
            }
        }
        (server.fields[HEADERS_FIELD] as? JsonObject)?.fields?.values?.forEach { value ->
            environmentVariableNames += McpEnvironmentVariables.collectFrom((value as? JsonString)?.value)
        }
        listOf(AUTH_FIELD, OAUTH_FIELD).forEach { field ->
            server.fields[field]?.stringValuesRecursive()?.forEach { value ->
                environmentVariableNames += McpEnvironmentVariables.collectFrom(value)
            }
        }
        environmentVariableNames += McpEnvironmentVariables.collectFrom(
            rawCommand,
            rawUrl,
            rawEnvironmentFile,
            *rawArgs.toTypedArray(),
        )

        val privateConfiguration = listOf(AUTH_FIELD, OAUTH_FIELD).mapNotNull { field ->
            server.fields[field]?.let { field to it }
        }.toMap().takeIf { it.isNotEmpty() }?.let(::JsonObject)
        return RawMcpServer(
            agentId = agentId,
            name = name.trim(),
            transport = transport,
            command = McpSecretSanitizer.sanitizeCommand(rawCommand),
            args = McpSecretSanitizer.sanitizeArguments(rawArgs),
            url = McpSecretSanitizer.sanitizeUrl(rawUrl),
            environmentVariableNames = McpEnvironmentVariables.normalized(environmentVariableNames),
            configPath = configPath.toAbsolutePath().normalize().toString(),
            scope = scope,
            projectName = projectName,
            environmentFile = McpSecretSanitizer.sanitizeValue(rawEnvironmentFile),
            privateConfigurationFingerprint = privateFingerprint(privateConfiguration),
        )
    }

    private fun JsonObject.string(field: String): String? = (fields[field] as? JsonString)?.value

    private fun JsonObject.stringArray(field: String): List<String> =
        (fields[field] as? JsonArray)?.stringValues().orEmpty()

    private fun JsonArray.stringValues(): List<String> = values.mapNotNull { (it as? JsonString)?.value }

    private fun JsonValue.stringValuesRecursive(): Sequence<String> = when (this) {
        is JsonString -> sequenceOf(value)
        is JsonArray -> values.asSequence().flatMap { it.stringValuesRecursive() }
        is JsonObject -> fields.values.asSequence().flatMap { it.stringValuesRecursive() }
        JsonScalar -> emptySequence()
    }

    private fun privateFingerprint(value: JsonValue?): String? {
        if (value == null) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(PRIVATE_FINGERPRINT_SALT)
        digest.update(value.canonicalForm().toByteArray(StandardCharsets.UTF_8))
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun JsonValue.canonicalForm(): String = when (this) {
        is JsonString -> "s${value.length}:$value"
        is JsonArray -> values.joinToString(prefix = "[", postfix = "]", separator = ",") { it.canonicalForm() }
        is JsonObject -> fields.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
                "${key.length}:$key=${value.canonicalForm()}"
            }
        JsonScalar -> "scalar"
    }

    private const val TYPE_FIELD = "type"
    private const val COMMAND_FIELD = "command"
    private const val ARGS_FIELD = "args"
    private const val URL_FIELD = "url"
    private const val HTTP_URL_FIELD = "httpUrl"
    private const val SERVER_URL_FIELD = "serverUrl"
    private const val ENV_FIELD = "env"
    private const val ENVIRONMENT_FIELD = "environment"
    private const val ENV_FILE_FIELD = "envFile"
    private const val HEADERS_FIELD = "headers"
    private const val AUTH_FIELD = "auth"
    private const val OAUTH_FIELD = "oauth"
    private const val MAXIMUM_SERVERS = 20_000
    private val PRIVATE_FINGERPRINT_SALT = ByteArray(32).also(SecureRandom()::nextBytes)
}
