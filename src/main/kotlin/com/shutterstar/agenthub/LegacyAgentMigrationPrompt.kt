package com.shutterstar.agenthub

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.util.concurrent.TimeUnit

/**
 * One-off offer to replace an archived CLI with its successor (see [LegacyAgentMigration]).
 * Never acts silently: the user gets a notification, then the usual confirm-the-command dialog.
 */
object LegacyAgentMigrationPrompt {
    private const val MIGRATION_TIMEOUT_MINUTES = 5L

    /** Blocking (runs the CLI's `--version`); call from a background thread after detection. */
    fun offerIfNeeded(project: Project) {
        val settings = AgentSettingsState.getInstance()
        val detected = settings.getDetectionResults() ?: return
        for (migration in LegacyAgentMigration.all) {
            if (detected[migration.agentId] != true || settings.isMigrationDismissed(migration.agentId)) continue
            val agent = CodingAgents.byId(migration.agentId) ?: continue
            if (!migration.isLegacyOutput(AgentDetector.shellOutput("${agent.command} --version"))) continue
            notifyOffer(project, migration, agent)
        }
    }

    private fun notifyOffer(project: Project, migration: LegacyAgentMigration.Migration, agent: CodingAgent) {
        NotificationGroupManager.getInstance().getNotificationGroup("AgentHub")
            .createNotification(
                "AgentHub — ${agent.name}",
                "The installed ${agent.name} is an archived version that is no longer maintained and fails " +
                    "when it tries to update itself. Replace it with the new ${migration.newPackage}?",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring("Migrate…") { migrate(project, migration, agent) })
            .addAction(NotificationAction.createSimpleExpiring("Don't ask again") {
                AgentSettingsState.getInstance().dismissMigration(migration.agentId)
            })
            .notify(project)
    }

    private fun migrate(project: Project, migration: LegacyAgentMigration.Migration, agent: CodingAgent) {
        val powerShell = OsDetector.isWindows() && !WslSupport.isActive()
        val command = LegacyAgentMigration.command(migration, powerShell)
        val background = AgentSettingsState.getInstance().getState().runInBackground
        val context = "${agent.name} will be replaced by ${migration.newPackage}. The old install is removed only " +
            "after the new one installed successfully. You will need to /login again."
        if (!TerminalCommandRunner.confirmRun(project, "Migrate ${agent.name}", command, context, background)) return
        if (!background) {
            TerminalCommandRunner.run(project, "Migrate ${agent.name}", command)
            DetectionResultsWatcher.showNotification(
                project,
                "Migrate ${agent.name}",
                "Run Detect in Settings once the terminal is done.",
                NotificationType.INFORMATION,
            )
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val process = runCatching {
                ProcessBuilder(TerminalCommandRunner.backgroundArgv(command)).redirectErrorStream(true).start()
            }.getOrNull()
            if (process != null) {
                Thread { process.inputStream.readBytes() }.also { it.isDaemon = true; it.start() }
                if (!process.waitFor(MIGRATION_TIMEOUT_MINUTES, TimeUnit.MINUTES)) process.destroyForcibly()
            }
            val output = AgentDetector.shellOutput("${agent.command} --version")
            val ok = output.isNotBlank() && !migration.isLegacyOutput(output)
            DetectionResultsWatcher.showNotification(
                project,
                "Migrate ${agent.name}",
                if (ok) {
                    "${agent.name} migrated to ${migration.newPackage}"
                } else {
                    "${agent.name} migration may have failed - run it manually in a terminal"
                },
                if (ok) NotificationType.INFORMATION else NotificationType.WARNING,
            )
        }
    }
}
