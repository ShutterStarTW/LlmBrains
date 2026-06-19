package com.shutterstar.agenthub

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.components.Service

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
        var runInBackground: Boolean = true,
        var defaultsApplied: Boolean = false,
    )

    private var state: State = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
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
    }

    fun getLastDetectedPluginVersion(): String = state.lastDetectedPluginVersion

    fun saveLastDetectedPluginVersion(version: String) {
        state.lastDetectedPluginVersion = version
    }

    fun getOutdatedAgentIds(): Set<String> = state.outdatedAgentIds.toSet()

    fun saveOutdatedAgents(ids: List<String>) {
        state.outdatedAgentIds = ids.toMutableList()
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
