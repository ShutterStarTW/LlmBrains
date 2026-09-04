package com.shutterstar.agenthub.environment.persistence

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.shutterstar.agenthub.environment.model.ProjectEnvironment
import java.time.Instant

@Service(Service.Level.APP)
@State(name = "AgentHubEnvironmentIndex", storages = [Storage("AgentHubEnvironmentIndex.xml")])
class EnvironmentIndexService(
    private val now: () -> Instant = Instant::now,
) : PersistentStateComponent<EnvironmentIndexState> {
    @Volatile
    private var state = EnvironmentIndexState()

    override fun getState(): EnvironmentIndexState = state

    override fun loadState(state: EnvironmentIndexState) {
        this.state = state
    }

    fun cachedEnvironment(projectId: String): ProjectEnvironment? =
        EnvironmentIndexStateMapper.decode(state)[projectId]

    @Synchronized
    fun record(
        projectId: String,
        environment: ProjectEnvironment,
    ) {
        val updated = EnvironmentIndexStateMapper.encodeProject(projectId, environment, now())
        val projects = state.projects
            .filterNot { it.projectId == projectId }
            .plus(updated)
            .sortedByDescending { it.refreshedAtEpochMillis }
            .take(MAX_PERSISTED_PROJECTS)
            .toMutableList()
        state = EnvironmentIndexState(projects = projects)
    }

    @Synchronized
    fun clear() {
        state = EnvironmentIndexState()
    }

    companion object {
        private const val MAX_PERSISTED_PROJECTS = 256

        fun getInstance(): EnvironmentIndexService = service()
    }
}
