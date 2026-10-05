package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.discovery.MimoHomeSupport
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.logging.Logger

/** What differs between OpenCode and its forks (Kilo): ids, config file stems, directories and env overrides. */
data class OpenCodeMcpFlavor(
    val agentId: String,
    /** `$XDG_CONFIG_HOME/<appName>`: the global config directory. */
    val appName: String,
    /** `<stem>.json` / `<stem>.jsonc` in every config directory. */
    val configStem: String,
    /** Project directories next to the project root that also hold config (`.opencode`, `.kilo`, `.kilocode`). */
    val projectDirectories: List<String>,
    /** Further global config directories below the home directory (`.kilo`, `.kilocode`). */
    val homeDirectories: List<String>,
    val configDirectoryEnv: String,
    val configFileEnv: String,
    /** Extra file names accepted in the global directory (Kilo also reads `config.json`). */
    val extraGlobalFileNames: List<String> = emptyList(),
    /** Replaces the `$XDG_CONFIG_HOME/<appName>` default where the fork has its own home variable (MiMo's `MIMOCODE_HOME`). */
    val globalDirectoryResolver: ((Path) -> Path)? = null,
) {
    companion object {
        val OPENCODE = OpenCodeMcpFlavor(
            agentId = "opencode",
            appName = "opencode",
            configStem = "opencode",
            projectDirectories = listOf(".opencode"),
            homeDirectories = emptyList(),
            configDirectoryEnv = "OPENCODE_CONFIG_DIR",
            configFileEnv = "OPENCODE_CONFIG",
        )
        val KILO = OpenCodeMcpFlavor(
            agentId = "kilo",
            appName = "kilo",
            configStem = "kilo",
            projectDirectories = listOf(".kilo", ".kilocode"),
            homeDirectories = listOf(".kilo", ".kilocode"),
            configDirectoryEnv = "KILO_CONFIG_DIR",
            configFileEnv = "KILO_CONFIG",
            extraGlobalFileNames = listOf("config.json"),
        )
        val MIMO = OpenCodeMcpFlavor(
            agentId = "mimo",
            appName = "mimocode",
            configStem = "mimocode",
            projectDirectories = listOf(".mimocode"),
            homeDirectories = emptyList(),
            configDirectoryEnv = "MIMOCODE_CONFIG_DIR",
            configFileEnv = "MIMOCODE_CONFIG",
            globalDirectoryResolver = MimoHomeSupport::configDirectory,
        )
    }
}

/** MCP discovery shared by OpenCode, Kilo and MiMo Code (same `mcp` config shape); see [OpenCodeMcpProvider], [KiloMcpProvider]. */
open class OpenCodeFamilyMcpProvider(
    private val flavor: OpenCodeMcpFlavor,
    homeDirectory: Path,
) : McpProvider {
    override val agentId: String = flavor.agentId
    private val globalDirectory = flavor.globalDirectoryResolver?.invoke(homeDirectory) ?: EnvHomeDirectorySupport.resolveXdgGuarded(
        "XDG_CONFIG_HOME",
        homeDirectory,
        ".config",
        flavor.appName,
    )
    private val homeDirectories = flavor.homeDirectories.map(homeDirectory::resolve)

    // Both also load `<stem>.json(c)` from the directory named by the config-dir env var.
    private val customDirectory = EnvHomeDirectorySupport.configuredDirectoryGuarded(flavor.configDirectoryEnv, homeDirectory)

    // The config-file env var names a single custom config file (documented: loaded between global and project config).
    private val customFile = EnvHomeDirectorySupport.configuredDirectoryGuarded(flavor.configFileEnv, homeDirectory)
        ?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }

    override fun discoverGlobal(): List<RawMcpServer> =
        (listOf(globalDirectory) + homeDirectories + listOfNotNull(customDirectory)).flatMap { directory ->
            val extras = if (directory == globalDirectory) flavor.extraGlobalFileNames else emptyList()
            discoverConfig(findConfig(directory, extras), McpScope.GLOBAL)
        } + discoverConfig(customFile, McpScope.GLOBAL)

    override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
        val root = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        // The project root config and the project directories' configs are all loaded (the latter override).
        return (listOf(root) + flavor.projectDirectories.map(root::resolve)).flatMap {
            discoverConfig(findConfig(it), McpScope.PROJECT, project.name)
        }
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

    private fun findConfig(directory: Path, extraFileNames: List<String> = emptyList()): Path? =
        (listOf("${flavor.configStem}.json", "${flavor.configStem}.jsonc") + extraFileNames)
            .map(directory::resolve)
            .firstOrNull { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }

    private fun readRoot(configPath: Path): JsonObject? = McpConfigRootReader.read(
        configPath,
        flavor.appName,
        LOG,
        jsonc = true,
        warnOnlyIfFileExists = false,
    )

    private companion object {
        const val MCP_FIELD = "mcp"
        const val SERVERS_FIELD = "servers"
        val LOG: Logger = Logger.getLogger(OpenCodeFamilyMcpProvider::class.java.name)
    }
}

class OpenCodeMcpProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : OpenCodeFamilyMcpProvider(OpenCodeMcpFlavor.OPENCODE, homeDirectory)

class KiloMcpProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : OpenCodeFamilyMcpProvider(OpenCodeMcpFlavor.KILO, homeDirectory)

/** MiMo Code CLI (an OpenCode fork): `mimocode.json(c)` in its config directory, the project root and `.mimocode/`. */
class MimoMcpProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : OpenCodeFamilyMcpProvider(OpenCodeMcpFlavor.MIMO, homeDirectory)
