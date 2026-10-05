package com.shutterstar.agenthub.projects.persistence

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.concurrency.AppExecutorUtil
import com.shutterstar.agenthub.AgentSettingsState
import com.shutterstar.agenthub.projects.discovery.ProjectDiscoveryResult
import com.shutterstar.agenthub.projects.discovery.ProjectDiscoveryService
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectComparators
import com.shutterstar.agenthub.projects.model.ProjectVisibility
import com.shutterstar.agenthub.storage.AgentHubStorage
import com.shutterstar.agenthub.storage.StateStore
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

@Service(Service.Level.APP)
class ProjectIndexService(
    private val discover: () -> ProjectDiscoveryResult = ::discoverInstalledAgents,
    private val executor: Executor = AppExecutorUtil.getAppExecutorService(),
    private val now: () -> Instant = Instant::now,
    /** Only agents detected as installed are shown; the persisted index is filtered on read. */
    private val isAgentVisible: (String) -> Boolean = ::isInstalledAgentVisible,
    /** Bumps whenever the installed-agent detection changes; a run that straddles a change is redone. */
    private val detectionGeneration: () -> Long = ::currentDetectionGeneration,
    /** False while no detection has completed: discovery would find nothing and must not wipe the index. */
    private val canDiscover: () -> Boolean = ::isInstallationKnownNow,
    private val store: StateStore<ProjectIndexState> = AgentHubStorage.cache("projects", ProjectIndexState::class.java, ::ProjectIndexState),
) {
    private var storedState: ProjectIndexState
        get() = store.snapshot()
        set(value) { store.update { value } }

    /** The stored state and its decoded projects, rebuilt only when the store's stamp moves (a snapshot is a full XML copy). */
    private class StoredView(val stamp: String, val state: ProjectIndexState) {
        val projects: List<DiscoveredProject> by lazy { ProjectIndexStateMapper.decode(state) }
    }

    @Volatile
    private var storedView: StoredView? = null

    private fun storedView(): StoredView {
        val stamp = store.stamp()
        storedView?.takeIf { it.stamp == stamp }?.let { return it }
        return StoredView(stamp, store.snapshot()).also { storedView = it }
    }

    @Volatile
    private var activeRefresh: CompletableFuture<ProjectDiscoveryResult>? = null

    /**
     * The last discovery result of this IDE session.
     */
    @Volatile
    private var liveProjects: List<DiscoveredProject>? = null
    @Volatile private var liveStamp: String? = null

    private val refreshListeners = CopyOnWriteArrayList<(ProjectDiscoveryResult) -> Unit>()

    val state: ProjectIndexState get() = storedState

    fun loadState(state: ProjectIndexState) {
        this.storedState = state
        liveProjects = null
    }

    /**
     * The index restricted to currently visible agents. The stored/live data itself stays as the
     * last discovery wrote it (already installed-only); this read-time filter covers the window
     * between a detection change and the rediscovery it triggers.
     */
    fun cachedProjects(): List<DiscoveredProject> {
        val currentStamp = store.stamp()
        if (liveStamp != currentStamp) liveProjects = null
        return ProjectVisibility.filter(liveProjects ?: storedView().projects, isAgentVisible)
    }

    /** Whether this IDE session has run discovery yet. */
    fun hasLiveResults(): Boolean = liveProjects != null

    fun lastRefreshedAt(): Instant? = storedView().state.refreshedAtEpochMillis
        .takeIf { it > 0L }
        ?.let(Instant::ofEpochMilli)

    fun addRefreshListener(listener: (ProjectDiscoveryResult) -> Unit): AutoCloseable {
        refreshListeners += listener
        return AutoCloseable { refreshListeners -= listener }
    }

    @Synchronized
    fun refreshInBackground(): CompletableFuture<ProjectDiscoveryResult> {
        activeRefresh?.takeUnless { it.isDone }?.let { return it }
        if (!canDiscover()) return CompletableFuture.completedFuture(ProjectDiscoveryResult(emptyList(), emptyList()))

        val refreshStore = store.bound()
        val refreshPartition = store.partition()
        val refresh = CompletableFuture.supplyAsync(::discoverConsistently, executor)
            .thenApply { result ->
                if (store.partition() != refreshPartition) {
                    throw CancellationException("AgentHub runtime changed during discovery")
                }
                val merged = result.copy(projects = preserveFailedProviders(result))
                refreshStore.update { ProjectIndexStateMapper.encode(merged.projects, now()) }
                liveProjects = merged.projects
                liveStamp = refreshStore.stamp()
                refreshListeners.forEach { listener -> runCatching { listener(merged) } }
                merged
            }
        activeRefresh = refresh
        refresh.whenComplete { _, _ ->
            synchronized(this) {
                if (activeRefresh === refresh) activeRefresh = null
            }
        }
        return refresh
    }

    /** Keeps previously indexed sessions for providers that failed this pass. */
    private fun preserveFailedProviders(result: ProjectDiscoveryResult): List<DiscoveredProject> {
        val failedAgentIds = result.warnings.mapTo(mutableSetOf()) { it.agentId }
        if (failedAgentIds.isEmpty()) return result.projects
        val previous = cachedProjects()
        val byId = result.projects.associateByTo(linkedMapOf()) { it.identity.id }
        previous.forEach { oldProject ->
            val retained = oldProject.agents.filter { it.agentId in failedAgentIds && isAgentVisible(it.agentId) }
            if (retained.isEmpty()) return@forEach
            val fresh = byId[oldProject.identity.id]
            val agents = (fresh?.agents.orEmpty().filterNot { it.agentId in failedAgentIds } + retained)
                .sortedBy { it.agentId }
            val base = fresh ?: oldProject
            byId[oldProject.identity.id] = base.copy(
                agents = agents,
                lastActivity = agents.mapNotNull { it.lastActivity }.maxOrNull(),
            )
        }
        return byId.values.sortedWith(ProjectComparators.discoveredProjectByRecency)
    }

    /** Re-runs discovery if the installed-agent set changes during a scan. */
    private fun discoverConsistently(): ProjectDiscoveryResult {
        var result: ProjectDiscoveryResult
        var attempts = 0
        do {
            val generation = detectionGeneration()
            result = discover()
            attempts++
        } while (generation != detectionGeneration() && attempts < MAX_DISCOVERY_ATTEMPTS)
        return result
    }

    @Synchronized
    fun cancelActiveRefresh() {
        activeRefresh?.cancel(true)
        activeRefresh = null
    }

    fun storageStamp(): String = store.externalStamp()

    companion object {
        private const val MAX_DISCOVERY_ATTEMPTS = 3

        fun getInstance(): ProjectIndexService = service()
    }
}

// Production defaults. They fall back to "everything visible / known" when the settings service is
// unavailable (plain unit tests without an IDE application), so fixtures are never hidden by accident.
internal fun discoverInstalledAgents(): ProjectDiscoveryResult {
    val visible = runCatching { AgentSettingsState.getInstance().visibleAgentIds() }.getOrNull()
    return ProjectDiscoveryService(isAgentRelevant = { agentId -> visible == null || agentId in visible }).discover()
}

internal fun isInstalledAgentVisible(agentId: String): Boolean =
    runCatching { AgentSettingsState.getInstance().isAgentVisible(agentId) }.getOrDefault(true)

internal fun currentDetectionGeneration(): Long =
    runCatching { AgentSettingsState.getInstance().detectionGeneration }.getOrDefault(0L)

internal fun isInstallationKnownNow(): Boolean =
    runCatching { AgentSettingsState.getInstance().isInstallationKnown() }.getOrDefault(true)
