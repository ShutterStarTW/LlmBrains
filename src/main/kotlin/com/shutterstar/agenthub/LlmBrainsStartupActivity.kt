package com.shutterstar.agenthub

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.shutterstar.agenthub.projects.launch.PendingAgentLaunchStore
import com.shutterstar.agenthub.projects.persistence.ProjectIndexService
import java.util.concurrent.atomic.AtomicBoolean
import com.shutterstar.agenthub.storage.AgentHubStorage

class LlmBrainsStartupActivity : ProjectActivity, DumbAware {

    companion object {
        private val updateCheckDone = AtomicBoolean(false)
        private val detectionStarted = AtomicBoolean(false)
        private val detectionFinished = AtomicBoolean(false)
        private val indexRefreshHooked = AtomicBoolean(false)
    }

    override suspend fun execute(project: Project) {
        AgentHubStorage.migrateLegacy()
        checkPendingAgentLaunch(project)
        hookIndexRefreshToDetection()

        val settings = AgentSettingsState.getInstance()
        val currentVersion = pluginVersion()
        val firstEverRun = settings.getLastDetectedPluginVersion().isEmpty()
        val versionChanged = settings.getLastDetectedPluginVersion() != currentVersion
        // Guarded with an AtomicBoolean: runActivity fires once per open project, possibly concurrently —
        // without this, N projects open at once would each spawn a 16-thread detection pass.
        // Every IDE start detects once (not only after a plugin update): the tool window shows only
        // agents whose CLI is installed *now*, so the installed set must be fresh.
        if (detectionStarted.compareAndSet(false, true)) {
            // Capture the agent ids known at the previous detection *before* this run overwrites them,
            // so a plugin update that adds new agents only auto-enables the ones actually installed.
            val previouslyKnownIds = settings.getDetectionResults()?.keys ?: emptySet()
            // Blocks until all agents are checked — checkForUpdates can safely use the results after this
            val detected = AgentDetector.autoDetectAndConfigure() != null
            if (detected && versionChanged) {
                if (firstEverRun) {
                    settings.applyFirstRunDefaultsIfNeeded()
                } else {
                    settings.deactivateNewUninstalledAgents(previouslyKnownIds)
                }
                settings.saveLastDetectedPluginVersion(currentVersion)
            }
            detectionFinished.set(true)
            // Project/environment discovery starts only from a completed detection (the detection
            // listener also re-runs it whenever the installed set changes later).
            if (detected) ProjectIndexService.getInstance().refreshInBackground()
        }

        if (detectionFinished.get() && updateCheckDone.compareAndSet(false, true)) {
            AgentDetector.checkForUpdates(project)
        }
    }

    /**
     * A change of the installed-agent set (Detect, Settings install/uninstall watcher, WSL switch)
     * must rediscover sessions: agents that just became visible have no rows in the index, and
     * ones that disappeared must not linger. Skipped while nothing is known so an environment
     * reset does not overwrite the persisted index with an empty result.
     */
    private fun hookIndexRefreshToDetection() {
        if (!indexRefreshHooked.compareAndSet(false, true)) return
        AgentSettingsState.getInstance().addDetectionListener {
            if (AgentSettingsState.getInstance().isInstallationKnown()) {
                ProjectIndexService.getInstance().refreshInBackground()
            }
        }
    }

    // Cross-process handoff consumer for "Open & Launch" targeting a different JetBrains
    // installation — see PendingAgentLaunchStore. A no-op unless a matching request is pending.
    private fun checkPendingAgentLaunch(project: Project) {
        val agentId = PendingAgentLaunchStore().consumeIfMatching(project.basePath) ?: return
        val agent = CodingAgents.byId(agentId) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                TerminalCommandRunner.runAgent(project, "🤖 ${agent.name} · ${project.name}", agent)
            }
        }
    }
}
