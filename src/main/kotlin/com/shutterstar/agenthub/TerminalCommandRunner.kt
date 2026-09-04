package com.shutterstar.agenthub

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

object TerminalCommandRunner {
    fun confirmRun(project: Project?, title: String, command: String, context: String? = null, background: Boolean = false): Boolean {
        val intro = context?.let { "$it\n\n" } ?: ""
        val wslSuffix = if (WslSupport.isActive()) {
            val distro = WslSupport.settings.distro.trim()
            if (distro.isEmpty()) " in WSL" else " in WSL ($distro)"
        } else ""
        val where = (if (background) "in the background" else "in a terminal") + wslSuffix
        val message = "${intro}Run this command $where?\n\n$command"
        return Messages.showYesNoDialog(project, message, title, Messages.getQuestionIcon()) == Messages.YES
    }

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

    fun runInBackground(project: Project?, title: String, command: String) {
        try {
            val shell = when {
                WslSupport.isActive() -> WslSupport.wrapArgv(command)
                OsDetector.isWindows() -> listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", command)
                else -> listOf("bash", "-lc", command)
            }
            ProcessBuilder(shell).redirectErrorStream(true).start()
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

    fun run(
        project: Project,
        title: String,
        command: String,
        workingDirectory: String?,
    ) {
        // WSL mode: wrap for the (PowerShell) terminal line. wsl.exe maps the terminal's working
        // directory to the matching /mnt/<drive> path, so the command starts in the project dir.
        val effectiveCommand = if (WslSupport.isActive()) WslSupport.wrapForTerminal(command) else command

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
    ) {
        val workingDir = workingDirectory ?: project.basePath ?: ""

        // Candidates ordered newest → oldest API; TerminalView is deprecated but present in 2023.x/2024.x,
        // TerminalToolWindowManager is the oldest fallback.
        val terminalApiClasses = listOf(
            "org.jetbrains.plugins.terminal.TerminalService",
            "org.jetbrains.plugins.terminal.TerminalView",
            "org.jetbrains.plugins.terminal.TerminalToolWindowManager",
        )
        if (terminalApiClasses.any { tryRunViaReflection(it, project, workingDir, title, command) }) return

        DetectionResultsWatcher.showNotification(
            project,
            "Error",
            "Could not open a terminal window. Please run manually: $command",
            NotificationType.ERROR,
        )
    }
}
