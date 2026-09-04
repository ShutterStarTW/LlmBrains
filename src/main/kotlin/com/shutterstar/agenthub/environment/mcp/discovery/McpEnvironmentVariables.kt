package com.shutterstar.agenthub.environment.mcp.discovery

internal object McpEnvironmentVariables {
    private val shellReference = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-[^}]*)?}")
    private val openCodeReference = Regex("\\{env:([A-Za-z_][A-Za-z0-9_]*)}", RegexOption.IGNORE_CASE)

    fun collectFrom(vararg values: String?): Set<String> =
        values.filterNotNull().flatMapTo(linkedSetOf()) { value ->
            sequenceOf(shellReference, openCodeReference)
                .flatMap { reference -> reference.findAll(value).map { match -> match.groupValues[1] } }
        }

    fun normalized(names: Iterable<String>): Set<String> =
        names
            .map(String::trim)
            .filter { it.isNotEmpty() && it.length <= MAXIMUM_NAME_LENGTH }
            .toSortedSet()

    private const val MAXIMUM_NAME_LENGTH = 256
}
