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
        /** The environment ([runtimeKey]) the agent/tool selection and detection fields above currently belong to. */
        var profileKey: String = "",
        /** The same fields of the other environments (Windows native, each WSL distribution), set aside while unused. */
        var otherProfiles: MutableList<RuntimeProfile> = mutableListOf(),
    )

    /**
     * What differs between the environments agents run in: which agents and tools are enabled and what is installed
     * there. The fields of the *current* environment live directly in [State] (so every reader keeps working and an
     * existing settings file needs no migration); switching the environment swaps them with this stash.
     */
    data class RuntimeProfile(
        var key: String = "",
        var inactiveAgentIds: MutableList<String> = mutableListOf(),
        var activeCompanionIds: MutableList<String> = mutableListOf(),
        var detectedInstalledIds: MutableList<String> = mutableListOf(),
        var detectedNotInstalledIds: MutableList<String> = mutableListOf(),
        var detectionTimestamp: Long = 0L,
        var outdatedAgentIds: MutableList<String> = mutableListOf(),
        var unverifiedAgentIds: MutableList<String> = mutableListOf(),
        var defaultsApplied: Boolean = false,
    )

    private var state: State = State()

    /** A profile that was just created for an environment seen for the first time gets its defaults after its first detection. */
    @Volatile
    private var defaultsAfterNextDetection = false

    init {
        if (state.profileKey.isEmpty()) state.profileKey = runtimeKey()
        syncWslSettings()
    }

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        // A settings file from before the per-environment profiles belongs to whatever environment is selected now.
        if (state.profileKey.isEmpty()) state.profileKey = runtimeKey()
        defaultsAfterNextDetection = state.profileKey != HOST_KEY && !state.defaultsApplied && getDetectionResults() == null
        syncWslSettings()
    }

    /** The environment the current agent/tool selection and detection results belong to. */
    fun runtimeKey(): String = runtimeKey(state.useWsl, state.wslDistro)

    /** Persist WSL mode and mirror it into [WslSupport] (the SDK-free layer cannot read this service). */
    fun setWslMode(useWsl: Boolean, distro: String) {
        state.useWsl = useWsl
        state.wslDistro = distro
        switchProfile(runtimeKey(useWsl, distro))
        syncWslSettings()
    }

    /**
     * Makes [newKey] the current environment: its selection and detection results (or empty ones, when it was never
     * used) replace the current ones, which are set aside under their own key.
     */
    private fun switchProfile(newKey: String) {
        val oldKey = state.profileKey.ifEmpty { HOST_KEY }
        if (oldKey == newKey) {
            state.profileKey = newKey
            return
        }
        state.otherProfiles.removeAll { it.key == oldKey }
        state.otherProfiles.add(
            RuntimeProfile(
                key = oldKey,
                inactiveAgentIds = state.inactiveAgentIds,
                activeCompanionIds = state.activeCompanionIds,
                detectedInstalledIds = state.detectedInstalledIds,
                detectedNotInstalledIds = state.detectedNotInstalledIds,
                detectionTimestamp = state.detectionTimestamp,
                outdatedAgentIds = state.outdatedAgentIds,
                unverifiedAgentIds = state.unverifiedAgentIds,
                defaultsApplied = state.defaultsApplied,
            ),
        )
        val stored = state.otherProfiles.firstOrNull { it.key == newKey }
        state.otherProfiles.removeAll { it.key == newKey }
        state.inactiveAgentIds = stored?.inactiveAgentIds ?: mutableListOf()
        state.activeCompanionIds = stored?.activeCompanionIds ?: mutableListOf()
        state.detectedInstalledIds = stored?.detectedInstalledIds ?: mutableListOf()
        state.detectedNotInstalledIds = stored?.detectedNotInstalledIds ?: mutableListOf()
        state.detectionTimestamp = stored?.detectionTimestamp ?: 0L
        state.outdatedAgentIds = stored?.outdatedAgentIds ?: mutableListOf()
        state.unverifiedAgentIds = stored?.unverifiedAgentIds ?: mutableListOf()
        state.defaultsApplied = stored?.defaultsApplied ?: false
        state.profileKey = newKey
        defaultsAfterNextDetection = stored == null
        detectionFailed = false
        // The installed set differs: whatever was derived from the old one (indexes, visibility) must be redone.
        detectionChanged()
    }

    private fun syncWslSettings() {
        WslSupport.settings = WslSupport.Settings(state.useWsl, state.wslDistro)
        // The data agents are discovered in follows the mode: look the distro's home up in the background right
        // away (a failed earlier lookup may succeed now), so discovery does not have to wait for wsl.exe.
        AgentRuntime.resetCache()
        AgentRuntime.warmUp()
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
        if (defaultsAfterNextDetection) {
            defaultsAfterNextDetection = false
            applyFirstRunDefaultsIfNeeded()
        }
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
        const val HOST_KEY = "host"

        fun getInstance(): AgentSettingsState = service()

        /** `host`, or `wsl:<distro>` (`wsl:default` for the default distribution). */
        fun runtimeKey(useWsl: Boolean, distro: String): String =
            if (useWsl) "wsl:" + distro.trim().lowercase(java.util.Locale.ROOT).ifEmpty { "default" } else HOST_KEY

        /** The environment in words, for headings: "Windows (native)" or "WSL (Ubuntu)". */
        fun runtimeLabel(useWsl: Boolean, distro: String): String =
            if (useWsl) "WSL (${distro.trim().ifEmpty { "default distribution" }})" else "Windows (native)"
    }
}
