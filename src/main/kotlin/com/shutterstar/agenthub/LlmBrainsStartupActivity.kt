package com.shutterstar.agenthub

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import java.util.concurrent.atomic.AtomicBoolean

class LlmBrainsStartupActivity : StartupActivity, DumbAware {

    companion object {
        private val updateCheckDone = AtomicBoolean(false)
        private val detectionStarted = AtomicBoolean(false)
    }

    override fun runActivity(project: Project) {
        val settings = AgentSettingsState.getInstance()
        val currentVersion = pluginVersion()
        val firstEverRun = settings.getLastDetectedPluginVersion().isEmpty()
        var needsDetect = settings.getLastDetectedPluginVersion() != currentVersion
        // Guarded with an AtomicBoolean: runActivity fires once per open project, possibly concurrently —
        // without this, N projects open at once after a plugin update would each spawn a 16-thread detection pass.
        if (needsDetect && detectionStarted.compareAndSet(false, true)) {
            // Blocks until all agents are checked — checkForUpdates can safely use the results after this
            AgentDetector.autoDetectAndConfigure()
            if (firstEverRun) settings.applyFirstRunDefaultsIfNeeded()
            settings.saveLastDetectedPluginVersion(currentVersion)
            needsDetect = false
        }

        if (!needsDetect && updateCheckDone.compareAndSet(false, true)) {
            AgentDetector.checkForUpdates(project)
        }
    }
}