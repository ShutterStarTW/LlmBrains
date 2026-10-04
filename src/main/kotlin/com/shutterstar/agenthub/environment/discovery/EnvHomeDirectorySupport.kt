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
        return configured?.let(::configuredPath)
            ?: Path.of(System.getProperty("user.home"), relativeDirName)
    }

    /** Resolves the first set env var in [envVars] (in order), else [default]. */
    fun resolveFirst(vararg envVars: String, default: () -> Path): Path =
        envVars.firstNotNullOfOrNull { name ->
            System.getenv(name)?.trim()?.takeIf(String::isNotEmpty)?.let(::configuredPath)
        } ?: default()

    /**
     * Same as [resolve], but only honors the env var when [homeDirectory] is still the system
     * default — this keeps tests that inject a custom home directory isolated from a real
     * env var set on the developer's machine.
     */
    fun resolveGuarded(
        envVar: String,
        homeDirectory: Path,
        relativeDirName: String,
        configuredValue: String? = System.getenv(envVar),
    ): Path {
        val defaultHome = Path.of(System.getProperty("user.home"))
        if (homeDirectory.toAbsolutePath().normalize() == defaultHome.toAbsolutePath().normalize()) {
            val configured = configuredValue?.trim()?.takeIf(String::isNotEmpty)
            configured?.let(::configuredPath)?.let { return it }
        }
        return homeDirectory.resolve(relativeDirName)
    }

    /**
     * Resolves an XDG-style config root: `$<envVar>/<appName>` when the env var is set, else
     * `<homeDirectory>/<defaultBaseDirName>/<appName>` (e.g. `~/.config/<appName>` for
     * `XDG_CONFIG_HOME`). Same test-isolation guard as [resolveGuarded] — the env var is only
     * honored when [homeDirectory] is still the system default.
     */
    fun resolveXdgGuarded(
        envVar: String,
        homeDirectory: Path,
        defaultBaseDirName: String,
        appName: String,
        configuredValue: String? = System.getenv(envVar),
    ): Path {
        val defaultHome = Path.of(System.getProperty("user.home"))
        if (homeDirectory.toAbsolutePath().normalize() == defaultHome.toAbsolutePath().normalize()) {
            val configured = configuredValue?.trim()?.takeIf(String::isNotEmpty)
            configured?.let(::configuredPath)?.let { return it.resolve(appName) }
        }
        return homeDirectory.resolve(defaultBaseDirName).resolve(appName)
    }

    private fun configuredPath(value: String): Path? = runCatching {
        val home = Path.of(System.getProperty("user.home"))
        when {
            value == "~" -> home
            value.startsWith("~/") || value.startsWith("~\\") -> home.resolve(value.substring(2))
            else -> Path.of(value)
        }
    }.getOrNull()
}
