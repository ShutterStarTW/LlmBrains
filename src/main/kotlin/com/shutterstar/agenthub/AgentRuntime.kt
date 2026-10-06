package com.shutterstar.agenthub

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Where the agents' data lives. Normally that is the user's home on the machine the IDE runs on. In WSL mode
 * ([WslSupport.isActive]) the agents run inside a WSL distribution, so their sessions, skills, MCP servers and
 * instruction files are in *its* home directory - which Windows reaches over the `\\wsl.localhost\<distro>` share.
 *
 * Everything that discovers agent data asks this object for the home directory and for path translation, so the
 * discovery code itself never needs to know about WSL. Pure JDK (no IntelliJ imports): it is part of the
 * standalone test compilation.
 *
 * Known limit of the share: Windows cannot follow Linux symlinks inside the distro, so such entries are invisible.
 */
object AgentRuntime {
    /** The distro the settings select, with its home directory and the share path Windows reaches it by. */
    data class WslEnvironment(val distro: String, val linuxHome: String, val shareRoot: String)

    private val DRIVE_PATH = Regex("""^([A-Za-z]):([\\/].*)?$""")
    private val MNT_PATH = Regex("""^/mnt/([A-Za-z])(?:/(.*))?$""")

    // -- Overridable collaborators (tests) --------------------------------------------------------

    internal var wslActive: () -> Boolean = WslSupport::isActive
    internal var wslLookup: (WslSupport.Settings) -> WslEnvironment? = ::queryWsl
    /** Separator of paths below the distro share: `\` on Windows (tests on other systems use their own). */
    internal var separator: Char = java.io.File.separatorChar
    internal var shareProbe: (String) -> Boolean = ::shareExists
    internal var nanoTime: () -> Long = System::nanoTime

    private fun shareExists(root: String): Boolean = runCatching { Files.isDirectory(Path.of(root)) }.getOrDefault(false)

    /** Puts every collaborator back to its production value (tests that swap them restore it afterwards). */
    internal fun resetForTests() {
        wslActive = WslSupport::isActive
        wslLookup = ::queryWsl
        separator = java.io.File.separatorChar
        shareProbe = ::shareExists
        nanoTime = System::nanoTime
        resetCache()
    }

    private class Cached(val environment: WslEnvironment?, val at: Long)

    private val cache = HashMap<String, Cached>()

    // -- Runtime identity ---------------------------------------------------------------------

    fun isWsl(): Boolean = wslActive()

    /** True when the agents run on Windows itself (not in a WSL distro): the Windows-only conventions apply. */
    fun isWindowsRuntime(): Boolean = OsDetector.isWindows() && !isWsl()

    /** The Windows (or macOS/Linux) user's own home - never the distro's. */
    fun hostHome(): Path = Path.of(System.getProperty("user.home"))

    /** The home directory of the environment the agents run in. */
    fun userHome(): Path {
        if (!isWsl()) return hostHome()
        val environment = wslEnvironment()
        return Path.of(
            if (environment == null) UNAVAILABLE_HOME else environment.shareRoot + environment.linuxHome.replace('/', separator),
        )
    }

    /** The distro's home as a Linux path (`/home/me`), for expanding `~` in paths agents recorded; null outside WSL mode. */
    fun linuxHome(): String? = wslEnvironment()?.linuxHome

    /** Changes whenever the environment the agents run in changes (mode, distro, or the lookup result). */
    fun scopeKey(): String = if (isWsl()) "wsl:${WslSupport.settings.distro.trim().lowercase(Locale.ROOT)}|${userHome()}" else "host"

    // -- Path translation (WSL mode only) -------------------------------------------------------

    /**
     * A path as an agent recorded it → a path Windows can open. Linux paths go through the distro share,
     * `/mnt/<drive>/...` becomes `<DRIVE>:\...`; Windows drive paths stay. Null when it cannot be translated.
     * Outside WSL mode the path is returned unchanged.
     */
    fun toHostPath(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (!isWsl()) return value
        if (DRIVE_PATH.matches(value)) return value
        MNT_PATH.matchEntire(value)?.let { match ->
            val drive = match.groupValues[1].uppercase(Locale.ROOT)
            val rest = match.groupValues[2].replace('/', separator)
            return if (rest.isEmpty()) "$drive:$separator" else "$drive:$separator$rest"
        }
        if (value.startsWith("/")) {
            val environment = wslEnvironment() ?: return null
            return environment.shareRoot + value.replace('/', separator)
        }
        return null
    }

    /**
     * The reverse: a Windows path inside the distro share → its Linux path; a Windows drive path → `/mnt/<drive>/...`.
     * Null outside WSL mode or when the path is neither.
     */
    fun toLinuxPath(hostPath: String): String? {
        if (!isWsl()) return null
        val value = hostPath.trim().replace('\\', '/')
        // The share first: a test (or an odd setup) may keep the distro's files below a drive path.
        wslEnvironment()?.let { environment ->
            val roots = (listOf(environment.shareRoot) + shareRoots(environment.distro)).map { it.replace('\\', '/') }
            for (root in roots) {
                if (value.startsWith(root, ignoreCase = true) && (value.length == root.length || value[root.length] == '/')) {
                    return value.substring(root.length).ifEmpty { "/" }
                }
            }
        }
        DRIVE_PATH.matchEntire(value)?.let { match ->
            val rest = match.groupValues[2].trimStart('/')
            return "/mnt/${match.groupValues[1].lowercase(Locale.ROOT)}" + if (rest.isEmpty()) "" else "/$rest"
        }
        return null
    }

    /**
     * Where to start a terminal that runs an agent in WSL mode: (the directory the IDE terminal starts in, the Linux
     * directory for `wsl.exe --cd`). A project inside the distro - given as a Linux path or as its share path - is no
     * usable Windows terminal directory, so the terminal starts in [fallbackTerminalDirectory] and wsl.exe changes
     * into the project; any other directory is passed on unchanged (wsl.exe maps a drive path to `/mnt/<drive>`).
     */
    fun terminalDirectories(workingDirectory: String?, fallbackTerminalDirectory: String?): Pair<String?, String?> {
        if (workingDirectory == null) return fallbackTerminalDirectory to null
        val inDistro = workingDirectory.startsWith("/") || workingDirectory.startsWith("\\\\")
        if (!inDistro) return workingDirectory to null
        val linux = if (workingDirectory.startsWith("/")) workingDirectory else toLinuxPath(workingDirectory)
        return fallbackTerminalDirectory to linux
    }

    // -- Runtime-bound caching ------------------------------------------------------------------

    /** A value that is rebuilt when the runtime changes (providers hold resolved home directories). */
    class Scoped<T>(private val factory: () -> T) {
        private class Entry<T>(val key: String, val value: T)

        @Volatile
        private var entry: Entry<T>? = null

        fun get(): T {
            val key = scopeKey()
            entry?.takeIf { it.key == key }?.let { return it.value }
            synchronized(this) {
                entry?.takeIf { it.key == key }?.let { return it.value }
                return Entry(key, factory()).also { entry = it }.value
            }
        }
    }

    fun <T> scoped(factory: () -> T): Scoped<T> = Scoped(factory)

    // -- WSL lookup ---------------------------------------------------------------------------------

    /**
     * The distro the agents run in; null when WSL mode is off or the lookup failed (retried after a short pause).
     * Starting `wsl.exe` can take seconds: on the UI thread the lookup is started in the background instead and the
     * caller sees "not available yet" (the next call, once it finished, sees the result).
     */
    fun wslEnvironment(): WslEnvironment? {
        if (!isWsl()) return null
        val settings = WslSupport.settings
        val key = settings.distro.trim().lowercase(Locale.ROOT)
        fresh(key)?.let { return it.environment }
        if (java.awt.EventQueue.isDispatchThread()) {
            warmUp()
            return null
        }
        return lookup(settings, key)
    }

    /** Starts the lookup for the current settings in the background (cheap when it is already cached). */
    fun warmUp() {
        if (!isWsl()) return
        val settings = WslSupport.settings
        val key = settings.distro.trim().lowercase(Locale.ROOT)
        if (fresh(key) != null || !warming.compareAndSet(false, true)) return
        Thread({
            try {
                lookup(settings, key)
            } finally {
                warming.set(false)
            }
        }, "AgentHub-WSL-lookup").apply { isDaemon = true }.start()
    }

    private val warming = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lookupLock = Any()

    /** A cached answer that may still be used: a found environment, or a recent failure. */
    private fun fresh(key: String): Cached? = synchronized(cache) {
        cache[key]?.takeIf { it.environment != null || nanoTime() - it.at < FAILURE_RETRY_NANOS }
    }

    private fun lookup(settings: WslSupport.Settings, key: String): WslEnvironment? = synchronized(lookupLock) {
        fresh(key)?.let { return it.environment }
        val found = runCatching { wslLookup(settings) }.getOrNull()
        synchronized(cache) { cache[key] = Cached(found, nanoTime()) }
        found
    }

    /** Forgets cached lookups (a distro that was not running may be reachable now). */
    fun resetCache() = synchronized(cache) { cache.clear() }

    private fun shareRoots(distro: String): List<String> = listOf("\\\\wsl.localhost\\$distro", "\\\\wsl$\\$distro")

    private fun queryWsl(settings: WslSupport.Settings): WslEnvironment? {
        // `--exec printenv` runs no shell: no profile output, no quoting layer. Both values are set by WSL itself.
        val process = ProcessBuilder(WslSupport.execArgv(listOf("printenv", "HOME", "WSL_DISTRO_NAME"), settings))
            .redirectErrorStream(true)
            .apply { environment()["WSL_UTF8"] = "1" }
            .start()
        val bytes = process.inputStream.readBytes()
        if (!process.waitFor(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        val lines = WslSupport.decodeWslOutput(bytes).lineSequence()
            .map { it.trim { char -> char.isWhitespace() || char == '\u0000' || char == '﻿' } }
            .filter { it.isNotEmpty() }
            .toList()
        val home = lines.getOrNull(0)?.takeIf { it.startsWith("/") } ?: return null
        val distro = lines.getOrNull(1) ?: settings.distro.trim().takeIf { it.isNotEmpty() } ?: return null
        // Windows 11 / current Windows 10 publish `\\wsl.localhost`; older builds only `\\wsl$`.
        val shareRoot = shareRoots(distro).firstOrNull(shareProbe) ?: shareRoots(distro).first()
        return WslEnvironment(distro, home, shareRoot)
    }

    private const val UNAVAILABLE_HOME = "\\\\wsl.localhost\\unavailable\\home"
    private const val QUERY_TIMEOUT_SECONDS = 25L
    private val FAILURE_RETRY_NANOS = TimeUnit.SECONDS.toNanos(30)
}
