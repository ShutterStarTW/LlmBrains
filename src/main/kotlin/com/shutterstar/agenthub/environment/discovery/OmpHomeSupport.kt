package com.shutterstar.agenthub.environment.discovery

import java.nio.file.Path

/**
 * Oh My Pi's (`omp`) native agent directory (docs: `config-usage.md`, `environment-variables.md`):
 * - default `~/.omp/agent`; `PI_CONFIG_DIR` changes the `.omp` directory name under the home directory;
 * - `PI_CODING_AGENT_DIR` replaces the whole agent directory, **for the default profile only**;
 * - a named profile (`OMP_PROFILE`, else the legacy `PI_PROFILE` when `OMP_PROFILE` is undefined; empty or
 *   `default` means the default profile) lives in `~/.omp/profiles/<name>/agent` and ignores `PI_CODING_AGENT_DIR`.
 *
 * Environment variables are honoured only while [home] is the system home (test isolation, same guard as
 * [EnvHomeDirectorySupport.resolveGuarded]). Relocation to `$XDG_*_HOME/omp` directories on macOS/Linux is not modelled.
 */
internal object OmpHomeSupport {
    private const val DEFAULT_CONFIG_DIRECTORY = ".omp"

    fun configDirectory(home: Path, env: (String) -> String? = System::getenv): Path =
        home.resolve(guarded(home, env)("PI_CONFIG_DIR")?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_CONFIG_DIRECTORY)

    fun agentDirectory(home: Path, env: (String) -> String? = System::getenv): Path {
        val read = guarded(home, env)
        val root = configDirectory(home, env)
        val profile = (read("OMP_PROFILE") ?: read("PI_PROFILE"))?.trim().orEmpty()
        if (profile.isNotEmpty() && !profile.equals("default", ignoreCase = true)) {
            return root.resolve("profiles").resolve(profile).resolve("agent")
        }
        read("PI_CODING_AGENT_DIR")?.trim()?.takeIf(String::isNotEmpty)?.let { return expand(it, home) }
        return root.resolve("agent")
    }

    private fun guarded(home: Path, env: (String) -> String?): (String) -> String? {
        val systemHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        return if (home.toAbsolutePath().normalize() == systemHome) env else { _ -> null }
    }

    private fun expand(value: String, home: Path): Path = when {
        value == "~" -> home
        value.startsWith("~/") || value.startsWith("~\\") -> home.resolve(value.substring(2))
        else -> Path.of(value)
    }
}
