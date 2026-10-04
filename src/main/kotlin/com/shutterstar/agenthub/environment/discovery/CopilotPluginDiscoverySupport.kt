package com.shutterstar.agenthub.environment.discovery

import com.shutterstar.agenthub.environment.mcp.discovery.JsonArray
import com.shutterstar.agenthub.environment.mcp.discovery.JsonObject
import com.shutterstar.agenthub.environment.mcp.discovery.JsonString
import com.shutterstar.agenthub.environment.mcp.discovery.JsonValue
import com.shutterstar.agenthub.environment.mcp.discovery.McpConfigFileReader
import com.shutterstar.agenthub.environment.mcp.discovery.SafeJsonParser
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class CopilotPlugin(
    val root: Path,
    val manifestPath: Path,
    val skillDirectories: List<Path>,
    val mcpConfigPaths: List<Path>,
    val inlineMcpServers: JsonObject?,
)

internal object CopilotPluginDiscoverySupport {
    fun discover(copilotDirectory: Path): List<CopilotPlugin> {
        val installed = copilotDirectory.resolve("installed-plugins")
        return childDirectories(installed).flatMap { marketplace -> childDirectories(marketplace) }
            .take(MAXIMUM_PLUGINS)
            .mapNotNull(::readPlugin)
    }

    private fun readPlugin(root: Path): CopilotPlugin? {
        val manifestPath = MANIFEST_PATHS.map(root::resolve)
            .firstOrNull { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) } ?: return null
        val manifest = McpConfigFileReader.read(manifestPath)?.let(SafeJsonParser::parseJsonc) as? JsonObject
            ?: return null
        val portable = (manifest.fields["\$schema"] as? JsonString)?.value?.contains("agent-plugins.org") == true
        val skillPaths = if (portable) listOf("skills") else manifest.paths("skills") ?: listOf("skills")
        val mcpField = manifest.fields["mcpServers"]
        val mcpPaths = if (portable) {
            listOf("mcp.json")
        } else if (mcpField is JsonString) {
            listOf(mcpField.value)
        } else {
            listOf(".mcp.json", ".github/mcp.json")
        }
        return CopilotPlugin(
            root = root,
            manifestPath = manifestPath,
            skillDirectories = skillPaths.mapNotNull { safeResolve(root, it) },
            mcpConfigPaths = mcpPaths.mapNotNull { safeResolve(root, it) },
            inlineMcpServers = mcpField as? JsonObject,
        )
    }

    private fun JsonObject.paths(field: String): List<String>? = when (val value = fields[field]) {
        is JsonString -> listOf(value.value)
        is JsonArray -> value.values.mapNotNull { (it as? JsonString)?.value }
        else -> null
    }

    private fun safeResolve(root: Path, relative: String): Path? = runCatching {
        val path = Path.of(relative)
        if (path.isAbsolute) return null
        val normalizedRoot = root.toAbsolutePath().normalize()
        normalizedRoot.resolve(path).normalize().takeIf { it.startsWith(normalizedRoot) }
    }.getOrNull()

    private fun childDirectories(root: Path): List<Path> {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return runCatching {
            Files.list(root).use { entries ->
                entries.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                    .limit(MAXIMUM_PLUGINS.toLong())
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private const val MAXIMUM_PLUGINS = 256
    private val MANIFEST_PATHS = listOf(
        "plugin.json",
        ".plugin/plugin.json",
        ".github/plugin.json",
        ".github/plugin/plugin.json",
        ".claude-plugin/plugin.json",
    )
}
