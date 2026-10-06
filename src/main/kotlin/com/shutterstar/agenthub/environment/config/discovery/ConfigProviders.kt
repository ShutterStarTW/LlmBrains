package com.shutterstar.agenthub.environment.config.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.discovery.MimoHomeSupport
import com.shutterstar.agenthub.environment.discovery.OmpHomeSupport
import java.nio.file.Path
import com.shutterstar.agenthub.OsDetector
import com.shutterstar.agenthub.AgentRuntime

private fun home(): Path = AgentRuntime.userHome()
private fun agentHome(home: Path, env: String, directory: String): Path =
    EnvHomeDirectorySupport.resolveGuarded(env, home, directory)

class ClaudeConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "claude",
    listOf(agentHome(homeDirectory, "CLAUDE_CONFIG_DIR", ".claude").resolve("settings.json"), homeDirectory.resolve(".claude.json")),
    listOf(".claude/settings.json", ".claude/settings.local.json"),
)

class CodexConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "codex", listOf(agentHome(homeDirectory, "CODEX_HOME", ".codex").resolve("config.toml")), listOf(".codex/config.toml"),
)

class GrokConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "grok", listOf(agentHome(homeDirectory, "GROK_HOME", ".grok").resolve("config.toml")),
    // settings.json: the older per-project format ({"model": ...}) that Grok projects still carry.
    listOf(".grok/config.toml", ".grok/settings.json"),
)

class QwenConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "qwen", listOf(homeDirectory.resolve(".qwen/settings.json")), listOf(".qwen/settings.json", ".qwen/settings.local.json"),
)

class KiroConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "kiro", listOf(agentHome(homeDirectory, "KIRO_HOME", ".kiro").resolve("settings/cli.json")), emptyList(),
)

class CopilotConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "copilot",
    listOf("settings.json", "permissions-config.json", "permissions-config", "lsp-config.json").map {
        agentHome(homeDirectory, "COPILOT_HOME", ".copilot").resolve(it)
    },
    listOf(".github/copilot/settings.json", ".github/copilot/settings.local.json"),
)

class CursorConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "cursor",
    listOf(cursorHome(homeDirectory).resolve("cli-config.json")), listOf(".cursor/cli.json"),
) {
    companion object {
        private fun cursorHome(homeDirectory: Path): Path {
            val defaultHome = homeDirectory.toAbsolutePath().normalize() == AgentRuntime.hostHome().toAbsolutePath().normalize()
            return if (defaultHome && !System.getenv("CURSOR_CONFIG_DIR").isNullOrBlank()) {
                agentHome(homeDirectory, "CURSOR_CONFIG_DIR", ".cursor")
            } else if (defaultHome && !AgentRuntime.isWindowsRuntime() && !System.getenv("XDG_CONFIG_HOME").isNullOrBlank()) {
                EnvHomeDirectorySupport.resolveXdgGuarded("XDG_CONFIG_HOME", homeDirectory, ".config", "cursor")
            } else homeDirectory.resolve(".cursor")
        }
    }
}

class OpenCodeConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "opencode",
    listOf("opencode.json", "opencode.jsonc", "tui.json", "cli.json").map {
        EnvHomeDirectorySupport.resolveXdgGuarded("XDG_CONFIG_HOME", homeDirectory, ".config", "opencode").resolve(it)
    } + listOf("opencode.json", "opencode.jsonc").map { homeDirectory.resolve(".opencode").resolve(it) },
    listOf("opencode.json", "opencode.jsonc", "tui.json"),
)

/**
 * Kilo Code CLI: `kilo.json(c)`/`config.json` and `tui.json(c)` in `~/.config/kilo`, `kilo.json(c)` in project `.kilo/`.
 * Kilo also loads `opencode.json(c)` next to them (its `ALL_CONFIG_FILES`), so those are inventoried too.
 */
class KiloConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "kilo",
    EnvHomeDirectorySupport.resolveXdgGuarded("XDG_CONFIG_HOME", homeDirectory, ".config", "kilo").let { directory ->
        listOf("kilo.json", "kilo.jsonc", "opencode.json", "opencode.jsonc", "config.json", "tui.json", "tui.jsonc").map(directory::resolve)
    },
    listOf(
        "kilo.json", "kilo.jsonc", "opencode.json", "opencode.jsonc",
        ".kilo/kilo.json", ".kilo/kilo.jsonc", ".kilo/opencode.json", ".kilo/opencode.jsonc",
        ".kilocode/kilo.json", ".kilocode/kilo.jsonc", ".kilocode/opencode.json", ".kilocode/opencode.jsonc",
        ".kilo/tui.json", ".kilo/tui.jsonc",
    ),
)

/** Kimi Code CLI: `config.toml` and `tui.toml` in the data root (`~/.kimi-code`, or `KIMI_CODE_HOME`). */
class KimiConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "kimi",
    EnvHomeDirectorySupport.resolveGuarded("KIMI_CODE_HOME", homeDirectory, ".kimi-code").let { directory ->
        listOf("config.toml", "tui.toml").map(directory::resolve)
    },
    emptyList(),
)

/** Oh My Pi: the global `config.yml`/`config.yaml` of the agent directory and the project `.omp/config.yml`; inventory only (YAML). */
class OmpConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "omp",
    OmpHomeSupport.agentDirectory(homeDirectory).let { directory -> listOf("config.yml", "config.yaml").map(directory::resolve) },
    listOf(".omp/config.yml", ".omp/config.yaml"),
)

/** CLI state file is mixed settings/credentials; highlights remain strictly allowlisted. */
class ClineConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "cline",
    // settings/providers.json holds the API keys and is deliberately not inventoried.
    agentHome(homeDirectory, "CLINE_DATA_DIR", ".cline/data").let {
        listOf(it.resolve("settings/global-settings.json"), it.resolve("globalState.json"))
    },
    emptyList(),
)

class AntigravityConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "antigravity",
    listOf(
        homeDirectory.resolve(".gemini/antigravity-cli/settings.json"),
        homeDirectory.resolve(".gemini/antigravity-cli/keybindings.json"),
        homeDirectory.resolve(".gemini/config/config.json"),
        homeDirectory.resolve(".gemini/config/hooks.json"),
    ),
    listOf(".agents/hooks.json"),
)

/** MiMo Code CLI (an OpenCode fork): `mimocode.json(c)`/`tui.json(c)` in its config directory, `mimocode.json(c)` in the project root and `.mimocode/`. */
class MimoConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "mimo",
    MimoHomeSupport.configDirectory(homeDirectory).let { directory ->
        listOf("mimocode.json", "mimocode.jsonc", "tui.json", "tui.jsonc").map(directory::resolve)
    },
    listOf("mimocode.json", "mimocode.jsonc", ".mimocode/mimocode.json", ".mimocode/mimocode.jsonc", ".mimocode/tui.json", ".mimocode/tui.jsonc"),
)

/** Mistral Vibe: `config.toml` in the vibe home (`~/.vibe`, or `VIBE_HOME`) and the project's `.vibe/config.toml`; inventory only (TOML). */
class VibeConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "vibe",
    listOf(agentHome(homeDirectory, "VIBE_HOME", ".vibe").resolve("config.toml")),
    listOf(".vibe/config.toml"),
)

/**
 * Junie CLI: `config.json`, `settings.json`, `allowlist.json` and `sandbox.json` in the junie home (`~/.junie`, or `JUNIE_HOME`) and
 * the project's `.junie/config.json`. Credentials (`secure_credentials.json`, `authentication-key`, `dpapi_credentials/`) are not inventoried.
 */
class JunieConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "junie",
    agentHome(homeDirectory, "JUNIE_HOME", ".junie").let { directory ->
        listOf("config.json", "settings.json", "allowlist.json", "sandbox.json").map(directory::resolve)
    },
    listOf(".junie/config.json"),
)

/** Freebuff: `settings.json` in the CLI config directory (`~/.config/manicode`, or `FREEBUFF_CONFIG_DIR`); `credentials.json` is deliberately not inventoried. */
class FreebuffConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "freebuff",
    listOf(agentHome(homeDirectory, "FREEBUFF_CONFIG_DIR", ".config/manicode").resolve("settings.json")),
    emptyList(),
)
