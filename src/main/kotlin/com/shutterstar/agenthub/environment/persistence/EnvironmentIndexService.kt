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
    private val index = CachedEnvironmentIndex(store)

    private var storedState: EnvironmentIndexState
        get() = store.snapshot()
        set(value) { store.update { value } }

    val state: EnvironmentIndexState get() = storedState

    fun loadState(state: EnvironmentIndexState) {
        this.storedState = state
    }

    fun cachedEnvironment(projectId: String): ProjectEnvironment? = index.decoded()[projectId]

    fun cachedEnvironments(): Map<String, ProjectEnvironment> = index.decoded()

    @Synchronized
    fun record(
        projectId: String,
        environment: ProjectEnvironment,
    ) {
        val moment = now()
        index.record(EnvironmentIndexStateMapper.encodeProject(projectId, environment, moment), moment.toEpochMilli(), MAX_PERSISTED_PROJECTS)
    }

    @Synchronized
    fun clear() {
        storedState = EnvironmentIndexState()
    }

    fun storageStamp(): String = store.externalStamp()

    companion object {
        private const val MAX_PERSISTED_PROJECTS = 256

        fun getInstance(): EnvironmentIndexService = service()
    }
}
