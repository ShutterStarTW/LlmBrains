package com.shutterstar.agenthub

/**
 * Agents whose original CLI was archived and replaced by a differently-packaged successor that keeps
 * the same command name (Kimi CLI/pip -> Kimi Code CLI/npm). A stale install keeps shadowing the
 * successor and tries to self-update into an error, so the plugin offers a one-off migration.
 *
 * Tiny JDK-only object (no IntelliJ imports) so it can be unit-tested with plain kotlinc.
 */
object LegacyAgentMigration {
    data class Migration(
        val agentId: String,
        /** Package installed by the migration (the successor). */
        val newPackage: String,
        /** Uninstall commands (tool to guard on, command) for every way the legacy CLI may have been installed. */
        val legacyUninstalls: List<Pair<String, String>>,
        val isLegacyOutput: (String) -> Boolean,
    )

    val all: List<Migration> = listOf(
        Migration(
            agentId = "kimi",
            newPackage = "@moonshot-ai/kimi-code",
            legacyUninstalls = listOf(
                "pip" to "pip uninstall -y kimi-cli",
                "uv" to "uv tool uninstall kimi-cli",
                "pipx" to "pipx uninstall kimi-cli",
            ),
            isLegacyOutput = ::isLegacyKimiOutput,
        ),
    )

    fun forAgent(agentId: String): Migration? = all.firstOrNull { it.agentId == agentId }

    private val legacyKimiVersion = Regex("""(?i)\bkimi(?:-cli)?,\s*version\s+[01]\.""")

    /** The Python CLI prints `kimi, version 1.x` (1.52 also adds an "is no longer maintained" notice). */
    fun isLegacyKimiOutput(output: String): Boolean =
        output.contains("no longer maintained", ignoreCase = true) || legacyKimiVersion.containsMatchIn(output)

    /**
     * Installs the successor FIRST and removes the legacy installs only if that succeeded, so a
     * missing npm or a failed install never leaves the user without a working CLI. Single quotes
     * only (no double quotes - see the WSL note in CLAUDE.md).
     */
    fun command(migration: Migration, powerShell: Boolean): String {
        val install = "npm install -g ${migration.newPackage}"
        val noNpm = "[x] npm not found - install Node.js first; the old CLI was left untouched"
        val failed = "[x] install failed - the old CLI was left untouched"
        return if (powerShell) {
            val removals = migration.legacyUninstalls.joinToString("; ") { (tool, cmd) ->
                "if (Get-Command $tool -ErrorAction SilentlyContinue) { $cmd }"
            }
            "if (Get-Command npm -ErrorAction SilentlyContinue) { $install; " +
                "if (\$LASTEXITCODE -eq 0) { $removals } else { Write-Host '$failed' } } " +
                "else { Write-Host '$noNpm' }"
        } else {
            val removals = migration.legacyUninstalls.joinToString("; ") { (tool, cmd) ->
                "if command -v $tool >/dev/null 2>&1; then $cmd; fi"
            }
            "if command -v npm >/dev/null 2>&1; then if $install; then $removals; else echo '$failed'; fi; " +
                "else echo '$noNpm'; fi"
        }
    }
}
