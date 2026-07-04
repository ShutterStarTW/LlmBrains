package com.shutterstar.agenthub

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * WSL execution mode: when enabled (Windows only), every agent command — launch, detection,
 * install/update/remove, helper scripts — runs inside the selected WSL distribution instead of
 * the native Windows shell.
 *
 * Pure JDK on purpose (no IntelliJ imports): this file is part of the standalone kotlinc test
 * compile. The platform layer ([AgentSettingsState]) mirrors the persisted settings into
 * [settings] on load/apply, so this object never has to touch the IDE service registry.
 */
object WslSupport {
    data class Settings(val useWsl: Boolean = false, val distro: String = "")

    @Volatile
    var settings: Settings = Settings()

    /** WSL mode is only meaningful on Windows, regardless of what the persisted flag says. */
    fun isActive(): Boolean = OsDetector.isWindows() && settings.useWsl

    // -- Command wrapping -------------------------------------------------------------------

    // bash -lic (login + interactive): Ubuntu's stock .bashrc returns early for non-interactive
    // shells, so plain -lc would miss nvm-managed npm installs — the install path of most agents.
    // --exec makes wsl.exe forward the arguments untouched (no Windows-side re-parsing).
    private const val BASH = "bash"
    private const val BASH_FLAGS = "-lic"

    /** Argv for ProcessBuilder-based (background/in-process) execution inside the distro. */
    fun wrapArgv(command: String): List<String> = buildList {
        add("wsl.exe")
        settings.distro.trim().takeIf { it.isNotEmpty() }?.let { add("-d"); add(it) }
        add("--exec")
        add(BASH)
        add(BASH_FLAGS)
        add(withToolchainGuard(command))
    }

    /**
     * One-line form typed into the IDE terminal (PowerShell on Windows). The inner command is
     * single-quoted for PowerShell (`'` → `''`); wsl.exe --exec hands it to `bash -lic` as a
     * single argument, so bash is the only layer that interprets `$`, `|` and quotes in it.
     */
    fun wrapForTerminal(command: String): String {
        val distro = settings.distro.trim()
        val distroPart = if (distro.isEmpty()) "" else "-d '${distro.replace("'", "''")}' "
        return "wsl.exe $distroPart--exec $BASH $BASH_FLAGS '${withToolchainGuard(command).replace("'", "''")}'"
    }

    // -- Native command check / toolchain guard ----------------------------------------------

    // WSL appends the Windows PATH by default (interop), so a bare `command -v` also finds
    // Windows-side shims under /mnt/* — e.g. every Windows-npm global package. Those must not
    // count as "present in the distro": detection would report agents installed that only exist
    // on Windows, and the toolchain guard would happily drive the Windows npm/pip. __lb_has
    // therefore rejects resolutions under /mnt.
    //
    // NO DOUBLE QUOTES anywhere in these snippets: Java's ProcessBuilder escapes an embedded
    // `"` as `\"` on the Windows command line and wsl.exe's parser does not undo that — bash
    // receives a mangled command ("unexpected EOF"). Unquoted expansions are safe here: `case`
    // words undergo no field splitting (POSIX), and the checked names contain no whitespace.
    private const val HAS_FN =
        "__lb_has() { case \$(command -v \$1 2>/dev/null) in ''|/mnt/*) return 1;; esac; };"

    /** Distro-native `command -v`: exit 0 only when [binary] resolves outside /mnt (see [HAS_FN]). */
    fun nativeCheck(binary: String): String = "$HAS_FN __lb_has $binary"

    // W1: install/update hints assume the package manager exists in the distro, but a fresh
    // Ubuntu ships neither pip nor npm. Each guard installs a same-named shell function only
    // when the distro-native binary is missing, so `a || pip …` fallback chains still reach `a`
    // and only the actual pip call is intercepted: pip is aliased to pip3 when only that exists,
    // otherwise the call fails with an actionable message instead of bash's bare
    // "command not found". A /mnt (Windows interop) resolution gets its own message — running
    // the Windows npm/pip from WSL would silently install to Windows, not into the distro,
    // and "npm is not installed" alone reads as a lie to someone who can type npm right there.
    // `pip3 $@` is unquoted on purpose (no-double-quote rule above); pip/npm hint arguments
    // are flags and package names, never whitespace-containing strings.
    private val TOOLCHAIN_GUARDS = listOf(
        Regex("""(^|[\s;&|(!])pip($|\s)""") to
            "if __lb_has pip; then :; elif __lb_has pip3; then pip() { pip3 \$@; }; " +
            "else case \$(command -v pip 2>/dev/null) in " +
            "/mnt/*) pip() { echo '[x] pip here is the Windows one (WSL interop) - it would install to Windows, not into the distro. Install pip in the distro first: sudo apt install python3-pip' >&2; return 127; };; " +
            "*) pip() { echo '[x] pip is not installed in this distro. Install it first: sudo apt install python3-pip' >&2; return 127; };; esac; fi;",
        Regex("""(^|[\s;&|(!])npm($|\s)""") to
            "if __lb_has npm; then :; else case \$(command -v npm 2>/dev/null) in " +
            "/mnt/*) npm() { echo '[x] npm here is the Windows one (WSL interop) - it would install to Windows, not into the distro. Install Node.js in the distro first: sudo apt install nodejs npm (or use nvm)' >&2; return 127; };; " +
            "*) npm() { echo '[x] npm is not installed in this distro. Install Node.js first: sudo apt install nodejs npm (or use nvm)' >&2; return 127; };; esac; fi;",
    )

    /**
     * Prefixes [command] with the shim-guards of the toolchains it invokes (token match, so
     * `pipx`/`pip3` do not trigger the `pip` guard); commands that use none pass through
     * unchanged — agent launches and `command -v` detection are unaffected.
     */
    fun withToolchainGuard(command: String): String {
        val guards = TOOLCHAIN_GUARDS.filter { (regex, _) -> regex.containsMatchIn(command) }
        return if (guards.isEmpty()) command
        else HAS_FN + " " + guards.joinToString(" ") { it.second } + " " + command
    }

    private val DRIVE_PATH_REGEX = Regex("""^([A-Za-z]):[\\/](.*)$""")

    /**
     * `C:\foo\bar` → `/mnt/c/foo/bar`. Non-drive-path arguments (agent id lists, URLs) pass
     * through unchanged apart from backslash normalization, so it is safe to apply to every
     * argument of a helper-script invocation.
     */
    fun toWslPath(windowsPath: String): String {
        val match = DRIVE_PATH_REGEX.find(windowsPath)
            ?: return windowsPath.replace('\\', '/')
        val (drive, rest) = match.destructured
        return "/mnt/${drive.lowercase()}/${rest.replace('\\', '/')}"
    }

    // -- Distro discovery -------------------------------------------------------------------

    /**
     * Registered distributions, default distro first (`wsl.exe --list --quiet` order); empty
     * when WSL is not installed or has no distros. wsl.exe prints UTF-16LE by default; WSL_UTF8=1
     * (Store WSL >= 0.64) switches it to UTF-8, and the NUL-byte heuristic in [decodeWslOutput]
     * covers older inbox versions that ignore the variable.
     */
    fun listDistros(): List<String> = try {
        val process = ProcessBuilder("wsl.exe", "--list", "--quiet")
            .redirectErrorStream(true)
            .apply { environment()["WSL_UTF8"] = "1" }
            .start()
        val bytes = process.inputStream.readBytes()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            emptyList()
        } else if (process.exitValue() != 0) {
            emptyList()
        } else {
            decodeWslOutput(bytes).lineSequence()
                // Strip whitespace plus stray NULs / BOM left over from UTF-16 decoding.
                .map { line -> line.trim { it.isWhitespace() || it == '\u0000' || it == '\uFEFF' } }
                .filter { it.isNotEmpty() }
                .toList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun decodeWslOutput(bytes: ByteArray): String =
        if (bytes.any { it == 0.toByte() }) String(bytes, StandardCharsets.UTF_16LE)
        else String(bytes, StandardCharsets.UTF_8)
}