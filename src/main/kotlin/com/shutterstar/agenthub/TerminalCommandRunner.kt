package com.shutterstar.agenthub

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

object TerminalCommandRunner {
    /** Settings actions stay busy until the launched command actually finishes. */
    internal fun runTracked(
        project: Project,
        title: String,
        command: String,
        background: Boolean,
        executionSettings: WslSupport.Settings,
        onComplete: (Int) -> Unit,
    ) {
        if (background) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val exitCode = runBackgroundProcess(backgroundArgv(command, executionSettings))
                ApplicationManager.getApplication().invokeLater({ onComplete(exitCode) }, ModalityState.any())
            }
            return
        }
        val powershell = OsDetector.isWindows() && !executionSettings.useWsl
        val directory = try {
            Files.createTempDirectory("agenthub-operation-")
        } catch (_: IOException) {
            onComplete(-1)
            return
        }
        val result = directory.resolve("exit-code")
        val script = directory.resolve(if (powershell) "run.ps1" else "run.sh")
        fun cleanup() {
            runCatching { Files.deleteIfExists(result) }
            runCatching { Files.deleteIfExists(script) }
            runCatching { Files.deleteIfExists(directory) }
        }
        fun executionPath(path: Path): String =
            if (OsDetector.isWindows() && executionSettings.useWsl) WslSupport.toWslPath(path.toString()) else path.toString()
        try {
            val scriptCommand = if (OsDetector.isWindows() && executionSettings.useWsl) {
                WslSupport.withToolchainGuard(command)
            } else command
            Files.writeString(script, trackedCommandScript(scriptCommand, executionPath(result), powershell))
            val scriptPath = executionPath(script)
            val launch = if (powershell) {
                "powershell -NoProfile -ExecutionPolicy Bypass -File '${scriptPath.replace("'", "''")}'"
            } else {
                "bash '${scriptPath.replace("'", "'\\''")}'"
            }
            val effectiveCommand = if (OsDetector.isWindows() && executionSettings.useWsl) {
                WslSupport.wrapForTerminal(launch, executionSettings)
            } else launch
            if (!runInTerminal(project, title, effectiveCommand, project.basePath)) {
                cleanup()
                onComplete(-1)
                return
            }
            DetectionResultsWatcher.watchCommandCompletion(project, result) { exitCode ->
                cleanup()
                onComplete(exitCode)
            }
        } catch (_: IOException) {
            cleanup()
            onComplete(-1)
        }
    }

    fun confirmRun(
        project: Project?, title: String, command: String, context: String? = null, background: Boolean = false,
        executionSettings: WslSupport.Settings = WslSupport.settings,
        confirmText: String = "Run",
    ): Boolean {
        val wslSuffix = if (OsDetector.isWindows() && executionSettings.useWsl) {
            val distro = executionSettings.distro.trim()
            if (distro.isEmpty()) " in WSL" else " in WSL ($distro)"
        } else ""
        val where = (if (background) "in the background" else "in a terminal") + wslSuffix
        // HTML message (Messages renders it as such) so the command can be shown in a monospace,
        // bordered block instead of plain wrapped text — long install one-liners (WSL, piped
        // installers) are much easier to read that way.
        val message = buildString {
            append("<html>")
            if (context != null) {
                append(escapeHtml(context)).append("<br><br>")
            }
            append("Run this command $where?")
            append("<div style='font-family: monospace; border: 1px solid gray; border-radius: 4px; padding: 6px; margin-top: 8px;'>")
            append(escapeHtml(command))
            append("</div>")
            append("</html>")
        }
        return Messages.showYesNoDialog(project, message, title, confirmText, "Cancel", Messages.getQuestionIcon()) == Messages.YES
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // Reflection-only API shape shared by all Terminal classes:
    // getInstance(Project) → createLocalShellWidget(String, String) → executeCommand(String).
    // Returns false on any failure so the caller can fall through to the next candidate class.
    private fun tryRunViaReflection(className: String, project: Project, workingDir: String, title: String, command: String): Boolean =
        try {
            val cls = Class.forName(className)
            val instance = cls.getMethod("getInstance", Project::class.java).invoke(null, project)
            val createWidget = cls.getMethod("createLocalShellWidget", String::class.java, String::class.java)
            val widget = createWidget.invoke(instance, workingDir, title)
            val exec = widget.javaClass.getMethod("executeCommand", String::class.java)
            exec.invoke(widget, command)
            true
        } catch (_: Throwable) {
            false
        }

    fun runRespectingSettings(project: Project, bgTitle: String, fgTitle: String, command: String) {
        if (AgentSettingsState.getInstance().getState().runInBackground)
            runInBackground(project, bgTitle, command)
        else
            run(project, fgTitle, command)
    }

    internal fun backgroundArgv(command: String, executionSettings: WslSupport.Settings = WslSupport.settings): List<String> = when {
        OsDetector.isWindows() && executionSettings.useWsl -> WslSupport.wrapArgv(command, executionSettings)
        OsDetector.isWindows() -> listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", command)
        else -> listOf("bash", "-lc", command)
    }

    fun runInBackground(project: Project?, title: String, command: String, executionSettings: WslSupport.Settings = WslSupport.settings) {
        try {
            ProcessBuilder(backgroundArgv(command, executionSettings)).redirectErrorStream(true).start()
        } catch (_: Exception) {
            DetectionResultsWatcher.showNotification(
                project,
                title,
                "Failed to start background process. Switch to terminal mode in Settings.",
                NotificationType.ERROR,
            )
        }
    }

    fun run(project: Project, title: String, command: String) {
        run(project, title, command, project.basePath)
    }

    /**
     * Starts [agent] interactively in an IDE terminal. Its optional [CodingAgent.launchFlags] are
     * probed against the installed CLI's `--help` on a pooled thread first (see [LaunchFlags]), so
     * this is safe to call from the EDT and never passes a flag an older version would reject.
     */
    fun runAgent(project: Project, title: String, agent: CodingAgent, workingDirectory: String? = project.basePath) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val command = LaunchFlags.build(agent.command, agent.launchFlags) { AgentDetector.shellOutput(it) }
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) run(project, title, command, workingDirectory)
            }, ModalityState.any())
        }
    }

    fun run(
        project: Project,
        title: String,
        command: String,
        workingDirectory: String?,
        executionSettings: WslSupport.Settings = WslSupport.settings,
    ) {
        // WSL mode: wrap for the (PowerShell) terminal line. wsl.exe maps the terminal's working
        // directory to the matching /mnt/<drive> path, so the command starts in the project dir.
        val effectiveCommand = if (OsDetector.isWindows() && executionSettings.useWsl) WslSupport.wrapForTerminal(command, executionSettings) else command

        runInTerminal(project, title, effectiveCommand, workingDirectory)
    }

    /** Runs a host command in the IDE terminal without applying the optional WSL wrapper. */
    fun runNative(project: Project, title: String, command: String) {
        runInTerminal(project, title, command, project.basePath)
    }

    private fun runInTerminal(
        project: Project,
        title: String,
        command: String,
        workingDirectory: String?,
    ): Boolean {
        val workingDir = workingDirectory ?: project.basePath ?: ""

        // Candidates ordered newest → oldest API; TerminalView is deprecated but present in 2023.x/2024.x,
        // TerminalToolWindowManager is the oldest fallback.
        val terminalApiClasses = listOf(
            "org.jetbrains.plugins.terminal.TerminalService",
            "org.jetbrains.plugins.terminal.TerminalView",
            "org.jetbrains.plugins.terminal.TerminalToolWindowManager",
        )
        if (terminalApiClasses.any { tryRunViaReflection(it, project, workingDir, title, command) }) return true

        DetectionResultsWatcher.showNotification(
            project,
            "Error",
            "Could not open a terminal window. Please run manually: $command",
            NotificationType.ERROR,
        )
        return false
    }
}

internal fun runBackgroundProcess(
    argv: List<String>,
    start: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
): Int = try {
    val process = start(argv)
    // Drain output while waiting: a full stdout pipe must not stall an installer.
    try {
        process.inputStream.use { it.transferTo(OutputStream.nullOutputStream()) }
    } catch (_: IOException) {
        // A closed output pipe does not mean the child has exited; still wait for it below.
    }
    process.waitFor()
} catch (_: IOException) {
    -1
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    -1
}

internal fun trackedCommandScript(command: String, resultPath: String, powershell: Boolean): String =
    if (powershell) {
        """
        ${'$'}agentHubExitCode = 1
        try {
            ${'$'}global:LASTEXITCODE = 0
            & {
        $command
            }
            ${'$'}agentHubSucceeded = ${'$'}?
            ${'$'}agentHubExitCode = if (${'$'}LASTEXITCODE -ne 0) { ${'$'}LASTEXITCODE } elseif (${'$'}agentHubSucceeded) { 0 } else { 1 }
        } finally {
            [System.IO.File]::WriteAllText('${resultPath.replace("'", "''")}', [string]${'$'}agentHubExitCode)
        }
        exit ${'$'}agentHubExitCode
        """.trimIndent()
    } else {
        """
        (
        $command
        )
        agenthub_exit_code=${'$'}?
        printf '%s' "${'$'}agenthub_exit_code" > '${resultPath.replace("'", "'\\''")}'
        exit "${'$'}agenthub_exit_code"
        """.trimIndent()
    }
