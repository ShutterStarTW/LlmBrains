package com.shutterstar.agenthub

import java.util.concurrent.ConcurrentHashMap

/**
 * Builds interactive launch command lines whose optional flags are added only when the installed
 * CLI actually advertises them in its `--help` output, so a launch-time workaround (e.g. codex's
 * `--no-daemon`) never breaks an older version that rejects unknown options.
 *
 * Pure JDK (no IntelliJ imports): the help source is injected. The probe is a blocking process
 * call, so callers must be off the EDT.
 */
object LaunchFlags {
    private const val CACHE_TTL_MS = 5 * 60_000L

    private class CachedHelp(val text: String, val timestamp: Long)

    private val helpCache = ConcurrentHashMap<String, CachedHelp>()

    /**
     * `<command> [<subcommand>] <supported flags…>`. A flag is included only if [help] output for
     * `<command> [<subcommand>] --help` mentions it as a whole option token; a failed/empty probe
     * yields the bare command (never a guessed flag).
     */
    fun build(
        command: String,
        flags: List<String>,
        subcommand: String = "",
        help: (String) -> String,
    ): String {
        val base = listOf(command, subcommand).filter { it.isNotBlank() }.joinToString(" ")
        if (flags.isEmpty()) return base
        val helpText = cachedHelp("$base --help", help)
        val supported = flags.filter { mentions(helpText, it) }
        return (listOf(base) + supported).joinToString(" ")
    }

    /** Whole-token match: `--no-daemon` must not match inside `--no-daemon-foo` or `x--no-daemon`. */
    internal fun mentions(helpText: String, flag: String): Boolean {
        if (helpText.isBlank() || flag.isBlank()) return false
        return Regex("(?<![\\w-])" + Regex.escape(flag) + "(?![\\w-])").containsMatchIn(helpText)
    }

    internal fun clearCache() = helpCache.clear()

    // Only successful (non-blank) probes are cached, so a transient failure is retried next launch;
    // the TTL lets a freshly updated CLI's new flags be picked up without an IDE restart.
    private fun cachedHelp(probe: String, help: (String) -> String): String {
        val now = System.currentTimeMillis()
        helpCache[probe]?.takeIf { now - it.timestamp < CACHE_TTL_MS }?.let { return it.text }
        val text = runCatching { help(probe) }.getOrDefault("")
        if (text.isNotBlank()) helpCache[probe] = CachedHelp(text, now)
        return text
    }
}
