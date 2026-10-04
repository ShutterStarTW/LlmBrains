package com.shutterstar.agenthub

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.components.Service
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.APP)
@State(name = "LlmBrainsAgentSettings", storages = [Storage("LlmBrainsAgentSettings.xml")])
class AgentSettingsState : PersistentStateComponent<AgentSettingsState.State> {
    data class State(
        var inactiveAgentIds: MutableList<String> = mutableListOf(),
        var customAgentEnabled: Boolean = false,
        var customAgentName: String = "",
        var customAgentCommand: String = "",
        var customAgentUrl: String = "",
        var detectedInstalledIds: MutableList<String> = mutableListOf(),
        var detectedNotInstalledIds: MutableList<String> = mutableListOf(),
        var detectionTimestamp: Long = 0L,
        var lastDetectedPluginVersion: String = "",
        var outdatedAgentIds: MutableList<String> = mutableListOf(),
        var unverifiedAgentIds: MutableList<String> = mutableListOf(),
        var runInBackground: Boolean = true,
        var defaultsApplied: Boolean = false,
        var activeCompanionIds: MutableList<String> = mutableListOf(),
        var useWsl: Boolean = false,
        var wslDistro: String = "",
        var dismissedMigrationIds: MutableList<String> = mutableListOf(),
    )

    private var state: State = State()

    init {
        syncWslSettings()
    }

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        syncWslSettings()
    }

    /** Persist WSL mode and mirror it into [WslSupport] (the SDK-free layer cannot read this service). */
    fun setWslMode(useWsl: Boolean, distro: String) {
        state.useWsl = useWsl
        state.wslDistro = distro
        syncWslSettings()
    }

    private fun syncWslSettings() {
        WslSupport.settings = WslSupport.Settings(state.useWsl, state.wslDistro)
    }

    fun isAgentActive(id: String): Boolean = id !in state.inactiveAgentIds

    fun setAgentActive(id: String, active: Boolean) {
        if (active) {
            state.inactiveAgentIds.remove(id)
        } else if (id !in state.inactiveAgentIds) {
            state.inactiveAgentIds.add(id)
        }
    }

    fun activeAgents(): List<CodingAgent> = CodingAgents.available().filter { isAgentActive(it.id) }

    fun isCompanionActive(id: String): Boolean = id in state.activeCompanionIds

    fun setCompanionActive(id: String, active: Boolean) {
        if (active) {
            if (id !in state.activeCompanionIds) state.activeCompanionIds.add(id)
        } else {
            state.activeCompanionIds.remove(id)
        }
    }

    fun activeCompanions(): List<CodingAgent> =
        CompanionTools.available().filter { isCompanionActive(it.id) }

    /**
     * On a fresh install, enable only a sensible default set — the top 10 agents plus any
     * detected-installed agents — instead of all 30. Runs once (guarded by [State.defaultsApplied]);
     * existing users keep their previous selection. Call after detection so installed agents are known.
     */
    fun applyFirstRunDefaultsIfNeeded() {
        if (state.defaultsApplied) return
        val activeIds = (CodingAgents.defaultActiveIds + state.detectedInstalledIds).toSet()
        state.inactiveAgentIds = CodingAgents.all.map { it.id }
            .filter { it !in activeIds }
            .toMutableList()
        state.defaultsApplied = true
    }

    /**
     * After a plugin update introduces new agents, keep the opt-out model from silently enabling
     * them: any agent absent from [previouslyKnownIds] (the agent ids seen at the previous detection)
     * that is NOT currently detected-installed is added to [State.inactiveAgentIds]. Newly-introduced
     * agents that *are* installed stay active. No-op when there is no prior baseline to compare against,
     * so an existing user's explicit selection is never overwritten.
     */
    fun deactivateNewUninstalledAgents(previouslyKnownIds: Set<String>) {
        if (previouslyKnownIds.isEmpty()) return
        val installed = state.detectedInstalledIds.toSet()
        CodingAgents.all.map { it.id }
            .filter { it !in previouslyKnownIds && it !in installed && it !in state.inactiveAgentIds }
            .forEach { state.inactiveAgentIds.add(it) }
    }

    fun getDetectionResults(): Map<String, Boolean>? {
        if (state.detectedInstalledIds.isEmpty() && state.detectedNotInstalledIds.isEmpty()) return null
        val result = mutableMapOf<String, Boolean>()
        state.detectedInstalledIds.forEach { result[it] = true }
        state.detectedNotInstalledIds.forEach { result[it] = false }
        return result
    }

    fun saveDetectionResults(results: Map<String, Boolean>) {
        val (installed, notInstalled) = results.entries.partition { it.value }
        state.detectedInstalledIds = installed.map { it.key }.toMutableList()
        state.detectedNotInstalledIds = notInstalled.map { it.key }.toMutableList()
        state.detectionTimestamp = System.currentTimeMillis()
        detectionFailed = false
        detectionChanged()
    }

    /**
     * Drops all detection state. Used when the execution environment changes (native ↔ WSL or
     * another distro): the installed-set differs per environment, so stale results would show
     * false "(not installed)" labels until the next detection.
     */
    fun clearDetectionResults() {
        state.detectedInstalledIds = mutableListOf()
        state.detectedNotInstalledIds = mutableListOf()
        state.outdatedAgentIds = mutableListOf()
        state.unverifiedAgentIds = mutableListOf()
        state.detectionTimestamp = 0L
        detectionFailed = false
        detectionChanged()
    }

    fun getDetectionTimestamp(): Long = state.detectionTimestamp

    fun updateDetectionResult(id: String, installed: Boolean) {
        if (installed) {
            if (id !in state.detectedInstalledIds) state.detectedInstalledIds.add(id)
            state.detectedNotInstalledIds.remove(id)
        } else {
            state.detectedInstalledIds.remove(id)
            if (id !in state.detectedNotInstalledIds) state.detectedNotInstalledIds.add(id)
        }
        state.detectionTimestamp = System.currentTimeMillis()
        detectionChanged()
    }

    // --- Installed-agent visibility (see InstalledAgentPolicy) -------------------------------

    /**
     * Bumped on every change to the detection result (new results, an install/uninstall watcher
     * update, an environment reset). Long-running discovery captures it up front and discards its
     * result if it moved, so a run that started before a detection finished never lands stale.
     */
    private val generation = AtomicLong()
    val detectionGeneration: Long get() = generation.get()

    /** True after a detection pass produced no usable result at all; cleared by any new result. */
    @Volatile
    var detectionFailed: Boolean = false
        private set

    private val detectionListeners = CopyOnWriteArrayList<() -> Unit>()

    /** Listener runs on whichever thread changed the detection state — marshal to the EDT yourself. */
    fun addDetectionListener(listener: () -> Unit): AutoCloseable {
        detectionListeners += listener
        return AutoCloseable { detectionListeners -= listener }
    }

    fun markDetectionFailed() {
        detectionFailed = true
        detectionChanged()
    }

    private fun detectionChanged() {
        generation.incrementAndGet()
        detectionListeners.forEach { listener -> runCatching { listener() } }
    }

    /** Whether the agent's sessions, skills, MCP servers and instructions may be shown. */
    fun isAgentVisible(agentId: String): Boolean =
        InstalledAgentPolicy.isVisible(agentId, getDetectionResults())

    /** Ids of the launcher agents (never companion tools) detected as installed right now. */
    fun visibleAgentIds(): Set<String> =
        InstalledAgentPolicy.visibleIds(CodingAgents.all.map { it.id }, getDetectionResults())

    /** False while there is no completed detection for the current environment. */
    fun isInstallationKnown(): Boolean = getDetectionResults() != null

    fun isMigrationDismissed(agentId: String): Boolean = agentId in state.dismissedMigrationIds

    fun dismissMigration(agentId: String) {
        if (agentId !in state.dismissedMigrationIds) state.dismissedMigrationIds.add(agentId)
    }

    fun getLastDetectedPluginVersion(): String = state.lastDetectedPluginVersion

    fun saveLastDetectedPluginVersion(version: String) {
        state.lastDetectedPluginVersion = version
    }

    fun getOutdatedAgentIds(): Set<String> = state.outdatedAgentIds.toSet()

    fun saveOutdatedAgents(ids: List<String>) {
        state.outdatedAgentIds = ids.toMutableList()
    }

    /** Installed agents whose update status could not be determined by the last update check. */
    fun getUnverifiedAgentIds(): Set<String> = state.unverifiedAgentIds.toSet()

    fun saveUnverifiedAgents(ids: List<String>) {
        state.unverifiedAgentIds = ids.toMutableList()
    }

    fun removeOutdatedAgent(id: String) {
        state.outdatedAgentIds.remove(id)
    }

    fun getCustomAgent(): CodingAgent? {
        if (!state.customAgentEnabled || state.customAgentName.isBlank() || state.customAgentCommand.isBlank()) {
            return null
        }
        return CodingAgent(
            id = "custom",
            name = state.customAgentName.trim(),
            command = state.customAgentCommand.trim(),
            versionArgs = "--version",
            installHint = "",
            updateHint = "",
            url = state.customAgentUrl.trim().ifBlank { "https://example.com" },
        )
    }

    companion object {
        fun getInstance(): AgentSettingsState = service()
    }
}
