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

    fun isCommandAvailable(command: String): Boolean = probeCommand(command) == true

    /**
     * Tri-state probe: `true`/`false` when the shell answered, `null` when the check itself could
     * not run (shell missing, timeout, interruption). Detection uses this so a broken probe is
     * "unknown" rather than a false "not installed" that would hide the agent's data.
     */
    fun probeCommand(command: String): Boolean? {
        return try {
            val process = if (WslSupport.isActive()) {
                // nativeCheck, not plain `command -v`: WSL's interop PATH would report every
                // Windows-side binary (/mnt/*) as installed in the distro.
                ProcessBuilder(WslSupport.wrapArgv(WslSupport.nativeCheck(command)))
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
    fun detectAllAgents(): Map<String, Boolean> {
        val agents = CodingAgents.detectable()
        val pool = Executors.newFixedThreadPool(minOf(agents.size, 16))
        return try {
            agents
                .map { agent -> agent.id to pool.submit(Callable { probeCommand(agent.command) }) }
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
    fun autoDetectAndConfigure(): Pair<Int, Int>? = applyDetection(detectAllAgents())

    private fun applyDetection(results: Map<String, Boolean>): Pair<Int, Int>? {
        if (results.isEmpty()) {
            AgentSettingsState.getInstance().markDetectionFailed()
            return null
        }
        return DetectionResultsWatcher.applyResults(results)
    }

    // [onDone] runs on the EDT after detection finishes.
    fun detectAndNotify(project: Project?, onDone: () -> Unit = {}) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = detectAllAgents()
            // ModalityState.any() so this runs while the modal Settings dialog is open (else deferred until close).
            ApplicationManager.getApplication().invokeLater({
                val applied = applyDetection(results)
                AgentSettingsConfigurable.scheduleRefresh()
                if (applied != null) {
                    DetectionResultsWatcher.showNotification(
                        project,
                        "Detect",
                        "${applied.first} / ${applied.second} agents installed",
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
        val detectionResults = settings.getDetectionResults() ?: return
        val installedAgents = CodingAgents.detectable().filter { detectionResults[it.id] == true }
        if (installedAgents.isEmpty()) return

        val outcome = UpdateChecker(runCommand = { runSilent(shellArgv(it), VERSION_TIMEOUT_SECONDS) }).check(installedAgents)
        settings.saveOutdatedAgents(outcome.outdated)
        settings.saveUnverifiedAgents(outcome.unverified)

        val byId = installedAgents.associateBy { it.id }
        val outdatedNames = outcome.outdated.mapNotNull { byId[it]?.name }
        val unverifiedNames = outcome.unverified.mapNotNull { byId[it]?.name }
        ApplicationManager.getApplication().invokeLater({
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
    private fun shellArgv(command: String): Array<String> = when {
        WslSupport.isActive() -> WslSupport.wrapArgv("$command 2>&1").toTypedArray()
        OsDetector.isWindows() -> arrayOf("cmd", "/c", command)
        else -> arrayOf("bash", "-lc", "$command 2>&1")
    }

    /** Combined stdout+stderr of [commandLine] in the active shell ("" on failure/timeout); blocking. */
    internal fun shellOutput(commandLine: String, timeoutSeconds: Long = 10): String =
        runSilent(shellArgv(commandLine), timeoutSeconds)

    private fun runSilent(cmd: Array<String>, timeoutSeconds: Long): String = try {
        val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread { output.append(process.inputStream.bufferedReader().readText()) }
            .also { it.isDaemon = true; it.start() }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(1000)
        output.toString()
    } catch (_: Exception) {
        ""
    }
}