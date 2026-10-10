package com.shutterstar.agenthub

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object AgentDetector {
    private const val TIMEOUT_MS = 5000L
    private const val VERSION_TIMEOUT_SECONDS = 30L

    private val EXTRA_PATHS = listOf(
        "/opt/homebrew/bin",      // Homebrew on Apple Silicon
        "/usr/local/bin",         // Homebrew on Intel Mac, common Linux
        "/home/linuxbrew/.linuxbrew/bin", // Linuxbrew
        System.getProperty("user.home") + "/.local/bin", // pipx, cargo, etc.
        System.getProperty("user.home") + "/.cargo/bin", // Rust/cargo
        System.getProperty("user.home") + "/bin",        // User binaries
    )

    private val extendedPath: String by lazy {
        val currentPath = System.getenv("PATH") ?: ""
        (EXTRA_PATHS + currentPath.split(":")).joinToString(":")
    }

    fun isCommandAvailable(command: String, executionSettings: WslSupport.Settings = WslSupport.settings): Boolean =
        probeCommand(command, executionSettings) == true

    /**
     * Tri-state probe: `true`/`false` when the shell answered, `null` when the check itself could
     * not run (shell missing, timeout, interruption). Detection uses this so a broken probe is
     * "unknown" rather than a false "not installed" that would hide the agent's data.
     */
    fun probeCommand(command: String, executionSettings: WslSupport.Settings = WslSupport.settings): Boolean? {
        return try {
            val process = if (OsDetector.isWindows() && executionSettings.useWsl) {
                // nativeCheck, not plain `command -v`: WSL's interop PATH would report every
                // Windows-side binary (/mnt/*) as installed in the distro.
                ProcessBuilder(WslSupport.wrapArgv(WslSupport.nativeCheck(command), executionSettings))
                    .redirectErrorStream(true)
                    .start()
            } else if (OsDetector.isWindows()) {
                ProcessBuilder("where", command)
                    .redirectErrorStream(true)
                    .start()
            } else {
                // Use zsh on macOS (default since Catalina), bash elsewhere
                val shell = if (OsDetector.isMac()) "zsh" else "bash"
                ProcessBuilder(shell, "-lc", "command -v $command")
                    .redirectErrorStream(true)
                    .apply {
                        environment()["PATH"] = extendedPath
                    }
                    .start()
            }

            val completed = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                return null
            }

            process.exitValue() == 0
        } catch (_: IOException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /** Definitive results only: an agent whose probe could not run is left out (= unknown). */
    fun detectAllAgents(executionSettings: WslSupport.Settings = WslSupport.settings): Map<String, Boolean> {
        val agents = (CodingAgents.all + CompanionTools.all).filterNot {
            OsDetector.isWindows() && !executionSettings.useWsl && it.unsupportedOnWindows
        }
        val pool = Executors.newFixedThreadPool(minOf(agents.size, 16))
        return try {
            agents
                .map { agent -> agent.id to pool.submit(Callable { probeCommand(agent.command, executionSettings) }) }
                .mapNotNull { (id, future) ->
                    val installed = try {
                        future.get()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        null
                    } catch (_: ExecutionException) {
                        null
                    }
                    installed?.let { id to it }
                }
                .toMap()
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Runs a full detection and stores it; `null` when the pass produced nothing usable (the
     * failure is recorded so the tool window can offer a retry — it is never read as "nothing
     * installed").
     */
    fun autoDetectAndConfigure(): Pair<Int, Int>? {
        val settings = AgentSettingsState.getInstance()
        val snapshot = settings.executionSnapshot()
        val results = detectAllAgents(snapshot.settings)
        var applied: Pair<Int, Int>? = null
        settings.applyIfCurrent(snapshot) { applied = applyDetection(results) }
        return applied
    }

    private fun applyDetection(results: Map<String, Boolean>): Pair<Int, Int>? {
        if (results.isEmpty()) {
            AgentSettingsState.getInstance().markDetectionFailed()
            return null
        }
        return DetectionResultsWatcher.applyResults(results)
    }

    // [onDone] runs on the EDT after detection finishes.
    fun detectAndNotify(project: Project?, onDone: () -> Unit = {}) {
        val settings = AgentSettingsState.getInstance()
        val snapshot = settings.executionSnapshot()
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = detectAllAgents(snapshot.settings)
            // ModalityState.any() so this runs while the modal Settings dialog is open (else deferred until close).
            ApplicationManager.getApplication().invokeLater({
                var applied: Pair<Int, Int>? = null
                if (!settings.applyIfCurrent(snapshot) { applied = applyDetection(results) }) {
                    onDone()
                    return@invokeLater
                }
                AgentSettingsConfigurable.scheduleRefresh()
                val summary = applied
                if (summary != null) {
                    DetectionResultsWatcher.showNotification(
                        project,
                        "Detect",
                        "${summary.first} / ${summary.second} agents installed",
                        NotificationType.INFORMATION,
                    )
                } else {
                    DetectionResultsWatcher.showNotification(
                        project,
                        "Detect",
                        "Could not detect installed agents — check the shell/WSL setup and try again",
                        NotificationType.WARNING,
                    )
                }
                onDone()
            }, ModalityState.any())
        }
    }

    fun checkForUpdates(project: Project, notifyIfUpToDate: Boolean = false) {
        val settings = AgentSettingsState.getInstance()
        val snapshot = settings.executionSnapshot()
        val detectionResults = settings.getDetectionResults() ?: return
        val installedAgents = (CodingAgents.all + CompanionTools.all).filter { detectionResults[it.id] == true }
        if (installedAgents.isEmpty()) return

        val outcome = UpdateChecker(
            runCommand = { runSilent(shellArgv(it, snapshot.settings), VERSION_TIMEOUT_SECONDS) },
            runCommandResult = { runSilentResult(shellArgv(it, snapshot.settings), VERSION_TIMEOUT_SECONDS) },
        ).check(installedAgents)
        if (!settings.applyIfCurrent(snapshot) {
                settings.saveOutdatedAgents(outcome.outdated)
                settings.saveUnverifiedAgents(outcome.unverified)
            }) return

        val byId = installedAgents.associateBy { it.id }
        val outdatedNames = outcome.outdated.mapNotNull { byId[it]?.name }
        val unverifiedNames = outcome.unverified.mapNotNull { byId[it]?.name }
        ApplicationManager.getApplication().invokeLater({
            if (!settings.isExecutionCurrent(snapshot)) return@invokeLater
            AgentSettingsConfigurable.scheduleRefresh()
            when {
                outdatedNames.isNotEmpty() -> DetectionResultsWatcher.showNotification(
                    project,
                    "Update",
                    "${outdatedNames.size} update${if (outdatedNames.size > 1) "s" else ""} available: ${outdatedNames.joinToString(", ")}",
                    NotificationType.WARNING,
                )
                notifyIfUpToDate -> DetectionResultsWatcher.showNotification(
                    project,
                    "Update",
                    if (unverifiedNames.isEmpty()) {
                        "All agents are up to date"
                    } else {
                        "No updates found · could not verify: ${unverifiedNames.joinToString(", ")}"
                    },
                    NotificationType.INFORMATION,
                )
            }
        }, ModalityState.any())
    }

    // Runs a command line in the active shell (WSL distro / cmd / login bash); 2>&1 because several
    // CLIs print their version on stderr.
    internal fun shellArgv(command: String, executionSettings: WslSupport.Settings = WslSupport.settings): Array<String> = when {
        OsDetector.isWindows() && executionSettings.useWsl -> WslSupport.wrapArgv("$command 2>&1", executionSettings).toTypedArray()
        OsDetector.isWindows() -> arrayOf("cmd", "/c", command)
        else -> arrayOf("bash", "-lc", "$command 2>&1")
    }

    /** Combined stdout+stderr of [commandLine] in the active shell ("" on failure/timeout); blocking. */
    internal fun shellOutput(commandLine: String, timeoutSeconds: Long = 10, executionSettings: WslSupport.Settings = WslSupport.settings): String =
        runSilent(shellArgv(commandLine, executionSettings), timeoutSeconds)

    private fun runSilent(cmd: Array<String>, timeoutSeconds: Long): String =
        runSilentResult(cmd, timeoutSeconds).let { if (it.exitCode == 0) it.output else "" }

    private fun runSilentResult(cmd: Array<String>, timeoutSeconds: Long): CommandOutput {
        var process: Process? = null
        return try {
            val running = ProcessBuilder(*cmd).redirectErrorStream(true).start().also { process = it }
            val output = java.util.concurrent.atomic.AtomicReference("")
            val reader = Thread {
                runCatching { running.inputStream.bufferedReader().use { it.readText() } }
                    .onSuccess(output::set)
            }.also { it.isDaemon = true; it.start() }
            if (!running.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                running.destroyForcibly()
                CommandOutput("", null)
            } else {
                reader.join(1000)
                if (reader.isAlive) CommandOutput("", null) else CommandOutput(output.get(), running.exitValue())
            }
        } catch (_: IOException) {
            CommandOutput("", null)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            CommandOutput("", null)
        } finally {
            process?.let { running ->
                if (running.isAlive) running.destroyForcibly()
                runCatching { running.inputStream.close() }
            }
        }
    }
}
