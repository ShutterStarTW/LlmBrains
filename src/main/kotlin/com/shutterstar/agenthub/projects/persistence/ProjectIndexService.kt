package com.shutterstar.agenthub.projects.persistence

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.concurrency.AppExecutorUtil
import com.shutterstar.agenthub.projects.discovery.ProjectDiscoveryResult
import com.shutterstar.agenthub.projects.discovery.ProjectDiscoveryService
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

@Service(Service.Level.APP)
@State(name = "AgentHubProjectIndex", storages = [Storage("AgentHubProjectIndex.xml")])
class ProjectIndexService(
    private val discover: () -> ProjectDiscoveryResult = { ProjectDiscoveryService().discover() },
    private val executor: Executor = AppExecutorUtil.getAppExecutorService(),
    private val now: () -> Instant = Instant::now,
) : PersistentStateComponent<ProjectIndexState> {
    @Volatile
    private var state = ProjectIndexState()

    @Volatile
    private var activeRefresh: CompletableFuture<ProjectDiscoveryResult>? = null

    private val refreshListeners = CopyOnWriteArrayList<(ProjectDiscoveryResult) -> Unit>()

    override fun getState(): ProjectIndexState = state

    override fun loadState(state: ProjectIndexState) {
        this.state = state
    }

    fun cachedProjects(): List<DiscoveredProject> = ProjectIndexStateMapper.decode(state)

    fun lastRefreshedAt(): Instant? = state.refreshedAtEpochMillis
        .takeIf { it > 0L }
        ?.let(Instant::ofEpochMilli)

    fun addRefreshListener(listener: (ProjectDiscoveryResult) -> Unit): AutoCloseable {
        refreshListeners += listener
        return AutoCloseable { refreshListeners -= listener }
    }

    @Synchronized
    fun refreshInBackground(): CompletableFuture<ProjectDiscoveryResult> {
        activeRefresh?.takeUnless { it.isDone }?.let { return it }

        val refresh = CompletableFuture.supplyAsync(discover, executor)
            .thenApply { result ->
                state = ProjectIndexStateMapper.encode(result.projects, now())
                refreshListeners.forEach { listener -> runCatching { listener(result) } }
                result
            }
        activeRefresh = refresh
        refresh.whenComplete { _, _ ->
            synchronized(this) {
                if (activeRefresh === refresh) activeRefresh = null
            }
        }
        return refresh
    }

    @Synchronized
    fun cancelActiveRefresh() {
        activeRefresh?.cancel(true)
        activeRefresh = null
    }

    companion object {
        fun getInstance(): ProjectIndexService = service()
    }
}
