package com.shutterstar.agenthub.environment.discovery

import java.nio.file.Path

/**
 * Resolves an agent's home directory from an env var override, falling back to a directory
 * relative to the user's home. Shared by every agent whose CLI honors a single "home" env var
 * (Copilot's `COPILOT_HOME`, Codex's `CODEX_HOME`, Grok's `GROK_HOME`, ...).
 */
internal object EnvHomeDirectorySupport {
    fun resolve(envVar: String, relativeDirName: String): Path {
        val configured = System.getenv(envVar)?.trim()?.takeIf(String::isNotEmpty)
        return configured?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: Path.of(System.getProperty("user.home"), relativeDirName)
    }

    /**
     * Same as [resolve], but only honors the env var when [homeDirectory] is still the system
     * default — this keeps tests that inject a custom home directory isolated from a real
     * env var set on the developer's machine.
     */
    fun resolveGuarded(envVar: String, homeDirectory: Path, relativeDirName: String): Path {
        val defaultHome = Path.of(System.getProperty("user.home"))
        if (homeDirectory.toAbsolutePath().normalize() == defaultHome.toAbsolutePath().normalize()) {
            val configured = System.getenv(envVar)?.trim()?.takeIf(String::isNotEmpty)
            configured?.let { runCatching { Path.of(it) }.getOrNull() }?.let { return it }
        }
        return homeDirectory.resolve(relativeDirName)
    }
}
