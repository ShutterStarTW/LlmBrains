package com.shutterstar.agenthub.environment.mcp.discovery

internal object McpSecretSanitizer {
    private val sensitiveName = Regex(
        "(?i)^(?:api[-_]?key|access[-_]?token|auth(?:orization)?|client[-_]?secret|dsn|env|header|password|secret|token)$",
    )
    private val inlineSecret = Regex(
        "(?i)(api[-_]?key|access[-_]?token|auth(?:orization)?|client[-_]?secret|password|secret|token)(=|:)([^\\s&]+)",
    )
    private val querySecret = Regex(
        "(?i)([?&](?:api[-_]?key|access[-_]?token|auth(?:orization)?|client[-_]?secret|password|secret|token)=)[^&#\\s]*",
    )
    private val uriUserInfo = Regex("(://)[^/@\\s]+@")
    private val bearerToken = Regex("(?i)(Bearer\\s+)[^\\s]+")

    fun sanitizeArguments(arguments: List<String>): List<String> {
        var redactNext = false
        return arguments.map { argument ->
            if (redactNext) {
                redactNext = false
                REDACTED
            } else {
                val flagName = argument
                    .substringBefore('=')
                    .trimStart('-')
                if (!argument.contains('=') && sensitiveName.matches(flagName)) {
                    redactNext = true
                    argument
                } else {
                    sanitizeText(argument)
                }
            }
        }
    }

    fun sanitizeCommand(command: String?): String? = command?.let(::sanitizeText)

    fun sanitizeUrl(url: String?): String? = url?.let(::sanitizeText)

    fun sanitizeValue(value: String?): String? = value?.let(::sanitizeText)

    private fun sanitizeText(value: String): String =
        value
            .replace(uriUserInfo, "$1$REDACTED@")
            .replace(querySecret, "$1$REDACTED")
            .replace(inlineSecret, "$1$2$REDACTED")
            .replace(bearerToken, "$1$REDACTED")

    private const val REDACTED = "<redacted>"
}
