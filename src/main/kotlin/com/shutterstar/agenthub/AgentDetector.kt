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
    private val WHITESPACE_REGEX = "\\s+".toRegex()

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

    fun isCommandAvailable(command: String): Boolean {
        return try {
            val process = if (OsDetector.isWindows()) {
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
                return false
            }

            process.exitValue() == 0
        } catch (e: IOException) {
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun detectAllAgents(): Map<String, Boolean> {
        val agents = CodingAgents.available()
        val pool = Executors.newFixedThreadPool(minOf(agents.size, 16))
        return try {
            agents
                .map { agent -> agent.id to pool.submit(Callable { isCommandAvailable(agent.command) }) }
                .associate { (id, future) ->
                    id to try {
                        future.get()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        false
                    } catch (e: ExecutionException) {
                        false
                    }
                }
        } finally {
            pool.shutdownNow()
        }
    }

    fun autoDetectAndConfigure(): Pair<Int, Int> = DetectionResultsWatcher.applyResults(detectAllAgents())

    // [onDone] runs on the EDT after detection finishes.
    fun detectAndNotify(project: Project?, onDone: () -> Unit = {}) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = detectAllAgents()
            // ModalityState.any() so this runs while the modal Settings dialog is open (else deferred until close).
            ApplicationManager.getApplication().invokeLater({
                val (enabled, total) = DetectionResultsWatcher.applyResults(results)
                AgentSettingsConfigurable.scheduleRefresh()
                DetectionResultsWatcher.showNotification(
                    project,
                    "Detect",
                    "$enabled / $total agents installed",
                    NotificationType.INFORMATION,
                )
                onDone()
            }, ModalityState.any())
        }
    }

    fun checkForUpdates(project: Project, notifyIfUpToDate: Boolean = false) {
        val settings = AgentSettingsState.getInstance()
        val detectionResults = settings.getDetectionResults() ?: return
        val installedAgents = CodingAgents.available().filter { detectionResults[it.id] == true }
        if (installedAgents.isEmpty()) return

        val outdatedIds = mutableListOf<String>()
        val outdatedNames = mutableListOf<String>()

        val npmAgents = installedAgents.filter { "npm" in it.updateHint }
        if (npmAgents.isNotEmpty()) {
            // --json exits with code 1 when packages are outdated; runSilent captures stdout regardless
            val output = runSilent(
                if (OsDetector.isWindows()) arrayOf("cmd", "/c", "npm outdated -g --json")
                else arrayOf("bash", "-lc", "npm outdated -g --json 2>/dev/null")
            )
            npmAgents.forEach { agent ->
                val pkg = packageNameFrom(agent.updateHint)
                if (pkg.isBlank()) return@forEach
                // JSON keys are always quoted — "\"pkg\"" avoids false matches on substrings
                if ("\"$pkg\"" in output) { outdatedIds += agent.id; outdatedNames += agent.name }
            }
        }

        val pipAgents = installedAgents.filter { "pip" in it.updateHint }
        if (pipAgents.isNotEmpty()) {
            val output = runSilent(
                if (OsDetector.isWindows()) arrayOf("cmd", "/c", "pip list --outdated --format=json")
                else arrayOf("bash", "-lc", "pip list --outdated --format=json 2>/dev/null")
            ).lowercase()
            pipAgents.forEach { agent ->
                val pkg = packageNameFrom(agent.updateHint).lowercase()
                if (pkg.isBlank()) return@forEach
                // Match exact "name":"pkg" — handles both compact and spaced JSON
                if ("\"name\":\"$pkg\"" in output || "\"name\": \"$pkg\"" in output) {
                    outdatedIds += agent.id; outdatedNames += agent.name
                }
            }
        }

        settings.saveOutdatedAgents(outdatedIds)

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
                    "All agents are up to date",
                    NotificationType.INFORMATION,
                )
            }
        }, ModalityState.any())
    }

    // Drops flags (tokens starting with `-`) so trailing options like `--registry=...` aren't
    // mistaken for the package name; returns the last remaining token.
    private fun packageNameFrom(updateHint: String): String =
        updateHint.trim().split(WHITESPACE_REGEX).filterNot { it.startsWith("-") }.lastOrNull() ?: ""

    private fun runSilent(cmd: Array<String>): String = try {
        val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread { output.append(process.inputStream.bufferedReader().readText()) }
            .also { it.isDaemon = true; it.start() }
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(1000)
        output.toString()
    } catch (_: Exception) {
        ""
    }
}