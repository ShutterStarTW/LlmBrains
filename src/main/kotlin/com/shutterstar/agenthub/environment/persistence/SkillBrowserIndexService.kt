package com.shutterstar.agenthub.environment.persistence

import com.shutterstar.agenthub.storage.AgentHubStorage
import com.shutterstar.agenthub.storage.StateStore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.shutterstar.agenthub.environment.model.ProjectEnvironment
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import java.time.Instant

/** Metadata snapshots for the standalone Skills tab, including global skills without a discovered project. */
@Service(Service.Level.APP)
class SkillBrowserIndexService(
    private val store: StateStore<EnvironmentIndexState> = AgentHubStorage.cache("skill-browser", EnvironmentIndexState::class.java, ::EnvironmentIndexState),
) {
    private val index = CachedEnvironmentIndex(store)

    private var storedState: EnvironmentIndexState
        get() = store.snapshot()
        set(value) { store.update { value } }

    val state: EnvironmentIndexState get() = storedState

    fun loadState(state: EnvironmentIndexState) {
        this.storedState = state
    }

    fun cachedSkills(contextKey: String): List<AgentSkill> =
        index.decoded()[contextKey]?.skills.orEmpty()

    /** The source paths that were links when [contextKey] was last scanned. */
    fun cachedLinkPaths(contextKey: String): Set<String> =
        store.snapshot().projects.firstOrNull { it.projectId == contextKey }?.linkPaths.orEmpty().toSet()

    @Synchronized
    fun record(contextKey: String, skills: List<AgentSkill>, linkPaths: Set<String> = emptySet()) {
        val environment = ProjectEnvironment(contextKey, emptySet(), skills, emptyList(), emptyList())
        val moment = Instant.now()
        val encoded = EnvironmentIndexStateMapper.encodeProject(contextKey, environment, moment)
        encoded.linkPaths = linkPaths.sorted().toMutableList()
        index.record(encoded, moment.toEpochMilli(), MAX_CONTEXTS)
    }

    fun storageStamp(): String = store.externalStamp()

    companion object {
        private const val MAX_CONTEXTS = 256
        fun getInstance(): SkillBrowserIndexService = service()
    }
}
