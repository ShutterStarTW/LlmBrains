package com.shutterstar.agenthub.environment.persistence

import com.shutterstar.agenthub.storage.AgentHubStorage
import com.shutterstar.agenthub.storage.StateStore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.shutterstar.agenthub.environment.model.ProjectEnvironment
import java.time.Instant

@Service(Service.Level.APP)
class EnvironmentIndexService(
    private val now: () -> Instant = Instant::now,
    private val store: StateStore<EnvironmentIndexState> = AgentHubStorage.cache("environment", EnvironmentIndexState::class.java, ::EnvironmentIndexState),
) {
    private var storedState: EnvironmentIndexState
        get() = store.snapshot()
        set(value) { store.update { value } }

    val state: EnvironmentIndexState get() = storedState

    fun loadState(state: EnvironmentIndexState) {
        this.storedState = state
    }

    fun cachedEnvironment(projectId: String): ProjectEnvironment? =
        EnvironmentIndexStateMapper.decode(storedState)[projectId]

    fun cachedEnvironments(): Map<String, ProjectEnvironment> = EnvironmentIndexStateMapper.decode(storedState)

    @Synchronized
    fun record(
        projectId: String,
        environment: ProjectEnvironment,
    ) {
        val updated = EnvironmentIndexStateMapper.encodeProject(projectId, environment, now())
        store.update { previous ->
            val projects = previous.projects
                .filterNot { it.projectId == projectId }
                .plus(updated)
                .sortedByDescending { it.refreshedAtEpochMillis }
                .take(MAX_PERSISTED_PROJECTS)
                .toMutableList()
            EnvironmentIndexState(projects = projects)
        }
    }

    @Synchronized
    fun clear() {
        storedState = EnvironmentIndexState()
    }

    fun storageStamp(): String = store.stamp()

    companion object {
        private const val MAX_PERSISTED_PROJECTS = 256

        fun getInstance(): EnvironmentIndexService = service()
    }
}
