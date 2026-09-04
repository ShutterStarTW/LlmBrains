package com.shutterstar.agenthub.environment.discovery

import com.shutterstar.agenthub.environment.mcp.discovery.JsonArray
import com.shutterstar.agenthub.environment.mcp.discovery.JsonObject
import com.shutterstar.agenthub.environment.mcp.discovery.JsonString
import com.shutterstar.agenthub.environment.mcp.discovery.JsonValue
import com.shutterstar.agenthub.environment.mcp.discovery.McpConfigFileReader
import com.shutterstar.agenthub.environment.mcp.discovery.SafeJsonParser
import java.nio.file.Files
import java.nio.file.Path

internal data class CursorLocalPlugin(
    val root: Path,
    val manifestPath: Path,
    val manifest: JsonObject,
    val cursorPlugin: Boolean,
)

internal object CursorPluginDiscoverySupport {
    fun discover(userHome: Path): List<CursorLocalPlugin> {
        val pluginsDirectory = userHome.resolve(LOCAL_PLUGINS_DIRECTORY)
        if (!Files.isDirectory(pluginsDirectory)) return emptyList()
        return runCatching {
            Files.list(pluginsDirectory).use { paths ->
                paths
                    .limit(MAXIMUM_LOCAL_PLUGIN_ENTRIES.toLong())
                    .filter { Files.isDirectory(it) }
                    .sorted()
                    .map { readPlugin(it) }
                    .filter { it != null }
                    .map { it!! }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    fun componentPaths(
        plugin: CursorLocalPlugin,
        manifestField: String,
        defaultRelativePath: String,
    ): List<Path> {
        if (!plugin.cursorPlugin) return listOf(plugin.root.resolve(defaultRelativePath))
        val configured = plugin.manifest.fields[manifestField] ?: return listOf(plugin.root.resolve(defaultRelativePath))
        return stringValues(configured).mapNotNull { safeResolve(plugin.root, it) }
    }

    fun stringValues(value: JsonValue?): List<String> = when (value) {
        is JsonString -> listOf(value.value)
        is JsonArray -> value.values
            .asSequence()
            .mapNotNull { (it as? JsonString)?.value }
            .take(MAXIMUM_COMPONENT_PATHS)
            .toList()
        else -> emptyList()
    }

    fun safeResolve(root: Path, relativePath: String): Path? = runCatching {
        val candidate = Path.of(relativePath)
        if (candidate.isAbsolute) return null
        val realRoot = root.toRealPath()
        realRoot.resolve(candidate).normalize().toRealPath().takeIf { it.startsWith(realRoot) }
    }.getOrNull()

    private fun readPlugin(root: Path): CursorLocalPlugin? {
        val cursorManifest = root.resolve(CURSOR_PLUGIN_MANIFEST)
        val agentManifest = root.resolve(AGENT_PLUGIN_MANIFEST)
        val manifestPath = when {
            Files.isRegularFile(cursorManifest) -> cursorManifest
            Files.isRegularFile(agentManifest) -> agentManifest
            else -> return null
        }
        val content = McpConfigFileReader.read(manifestPath) ?: return null
        val manifest = SafeJsonParser.parse(content) as? JsonObject ?: return null
        val name = (manifest.fields[NAME_FIELD] as? JsonString)?.value
        if (name.isNullOrBlank()) return null
        val realRoot = runCatching { root.toRealPath() }.getOrNull() ?: return null
        val realManifest = runCatching { manifestPath.toRealPath() }.getOrNull() ?: return null
        if (!realManifest.startsWith(realRoot)) return null
        return CursorLocalPlugin(
            root = realRoot,
            manifestPath = realManifest,
            manifest = manifest,
            cursorPlugin = manifestPath.endsWith(CURSOR_PLUGIN_MANIFEST),
        )
    }

    private const val MAXIMUM_LOCAL_PLUGIN_ENTRIES = 256
    private const val MAXIMUM_COMPONENT_PATHS = 256
    private const val NAME_FIELD = "name"
    private val LOCAL_PLUGINS_DIRECTORY = Path.of(".cursor", "plugins", "local")
    private val CURSOR_PLUGIN_MANIFEST = Path.of(".cursor-plugin", "plugin.json")
    private val AGENT_PLUGIN_MANIFEST = Path.of("plugin.json")
}
