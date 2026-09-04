package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.mcp.model.McpScope
import java.nio.file.Path

internal object JsonSettingsMcpSupport {
    fun discover(
        agentId: String,
        configPath: Path,
        scope: McpScope,
        containerPaths: List<List<String>> = listOf(listOf("mcpServers")),
        urlImpliesSse: Boolean = false,
        projectName: String? = null,
    ): List<RawMcpServer> {
        val content = McpConfigFileReader.read(configPath) ?: return emptyList()
        val root = SafeJsonParser.parseJsonc(content) as? JsonObject ?: return emptyList()
        val container = containerPaths.firstNotNullOfOrNull { path -> root.objectAt(path) } ?: return emptyList()
        return JsonMcpServerSupport.parseServers(agentId, container, configPath, scope, urlImpliesSse, projectName)
    }

    private fun JsonObject.objectAt(path: List<String>): JsonObject? {
        var current = this
        path.forEachIndexed { index, field ->
            val child = current.fields[field] as? JsonObject ?: return null
            if (index == path.lastIndex) return child
            current = child
        }
        return null
    }
}
