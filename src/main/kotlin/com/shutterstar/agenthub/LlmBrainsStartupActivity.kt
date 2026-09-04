package com.shutterstar.agenthub

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.shutterstar.agenthub.projects.launch.PendingAgentLaunchStore
import com.shutterstar.agenthub.projects.persistence.ProjectIndexService
import java.util.concurrent.atomic.AtomicBoolean

class LlmBrainsStartupActivity : ProjectActivity, DumbAware {

    companion object {
        private val updateCheckDone = AtomicBoolean(false)
        private val detectionStarted = AtomicBoolean(false)
        private val projectDiscoveryStarted = AtomicBoolean(false)
    }

    override suspend fun execute(project: Project) {
        checkPendingAgentLaunch(project)

        if (projectDiscoveryStarted.compareAndSet(false, true)) {
            ProjectIndexService.getInstance().refreshInBackground()
        }

        val settings = AgentSettingsState.getInstance()
        val currentVersion = pluginVersion()
        val firstEverRun = settings.getLastDetectedPluginVersion().isEmpty()
        var needsDetect = settings.getLastDetectedPluginVersion() != currentVersion
        // Guarded with an AtomicBoolean: runActivity fires once per open project, possibly concurrently —
        // without this, N projects open at once after a plugin update would each spawn a 16-thread detection pass.
        if (needsDetect && detectionStarted.compareAndSet(false, true)) {
            // Capture the agent ids known at the previous detection *before* this run overwrites them,
            // so a plugin update that adds new agents only auto-enables the ones actually installed.
            val previouslyKnownIds = settings.getDetectionResults()?.keys ?: emptySet()
            // Blocks until all agents are checked — checkForUpdates can safely use the results after this
            AgentDetector.autoDetectAndConfigure()
            if (firstEverRun) {
                settings.applyFirstRunDefaultsIfNeeded()
            } else {
                settings.deactivateNewUninstalledAgents(previouslyKnownIds)
            }
            settings.saveLastDetectedPluginVersion(currentVersion)
            needsDetect = false
        }

        if (!needsDetect && updateCheckDone.compareAndSet(false, true)) {
            AgentDetector.checkForUpdates(project)
        }
    }

    // Cross-process handoff consumer for "Open & Launch" targeting a different JetBrains
    // installation — see PendingAgentLaunchStore. A no-op unless a matching request is pending.
    private fun checkPendingAgentLaunch(project: Project) {
        val agentId = PendingAgentLaunchStore().consumeIfMatching(project.basePath) ?: return
        val agent = CodingAgents.all.firstOrNull { it.id == agentId } ?: return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                TerminalCommandRunner.run(project, "🤖 ${agent.name} · ${project.name}", agent.command)
            }
        }
    }
}
