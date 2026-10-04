package com.shutterstar.agenthub.environment.config.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import java.nio.file.Path
import com.shutterstar.agenthub.OsDetector

private fun home(): Path = Path.of(System.getProperty("user.home"))
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
            val defaultHome = homeDirectory.toAbsolutePath().normalize() == home().toAbsolutePath().normalize()
            return if (defaultHome && !System.getenv("CURSOR_CONFIG_DIR").isNullOrBlank()) {
                agentHome(homeDirectory, "CURSOR_CONFIG_DIR", ".cursor")
            } else if (defaultHome && !OsDetector.isWindows() && !System.getenv("XDG_CONFIG_HOME").isNullOrBlank()) {
                EnvHomeDirectorySupport.resolveXdgGuarded("XDG_CONFIG_HOME", homeDirectory, ".config", "cursor")
            } else homeDirectory.resolve(".cursor")
        }
    }
}

class OpenCodeConfigProvider(homeDirectory: Path = home()) : FileConfigProvider(
    "opencode",
    listOf("opencode.json", "opencode.jsonc", "tui.json", "cli.json").map {
        EnvHomeDirectorySupport.resolveXdgGuarded("XDG_CONFIG_HOME", homeDirectory, ".config", "opencode").resolve(it)
    },
    listOf("opencode.json", "opencode.jsonc", "tui.json"),
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
