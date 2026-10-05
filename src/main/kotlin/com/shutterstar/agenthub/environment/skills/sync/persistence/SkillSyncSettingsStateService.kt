package com.shutterstar.agenthub.environment.skills.sync.persistence

import com.shutterstar.agenthub.storage.AgentHubStorage
import com.shutterstar.agenthub.storage.StateStore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.shutterstar.agenthub.environment.skills.sync.settings.SkillSyncSettings

/** Shared production state; standalone tests default to an injectable in-memory store. */
@Service(Service.Level.APP)
class SkillSyncSettingsStateService(
    private val store: StateStore<SkillSyncSettingsState> = AgentHubStorage.state("sync-settings", SkillSyncSettingsState::class.java, ::SkillSyncSettingsState),
) {
    private var storedState: SkillSyncSettingsState
        get() = store.snapshot()
        set(value) { store.update { value } }

    val state: SkillSyncSettingsState get() = storedState

    fun loadState(state: SkillSyncSettingsState) {
        this.storedState = state
    }

    fun current(): SkillSyncSettings = SkillSyncSettingsStateMapper.toSettings(storedState)

    @Synchronized
    fun update(settings: SkillSyncSettings) {
        store.update { SkillSyncSettingsStateMapper.toState(settings) }
    }

    fun storageStamp(): String = store.externalStamp()

    companion object {
        fun getInstance(): SkillSyncSettingsStateService = service()
    }
}
