package com.shutterstar.agenthub.environment.skills.sync.persistence

import com.shutterstar.agenthub.storage.AgentHubStorage
import com.shutterstar.agenthub.storage.StateStore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAuditEntry
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAuditTrail
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey

/** Shared production state; standalone tests default to an injectable in-memory store. */
@Service(Service.Level.APP)
class SkillSyncAuditStateService(
    private val store: StateStore<SkillSyncAuditState> = AgentHubStorage.state("audit", SkillSyncAuditState::class.java, ::SkillSyncAuditState),
) : SyncAuditTrail {
    private var storedState: SkillSyncAuditState
        get() = store.snapshot()
        set(value) { store.update { value } }

    val state: SkillSyncAuditState get() = storedState

    fun loadState(state: SkillSyncAuditState) {
        this.storedState = state
    }

    override fun entriesFor(skillId: String): List<SyncAuditEntry> =
        SkillSyncAuditStateMapper.entriesFor(storedState, skillId)

    override fun entriesFor(key: SkillInstanceKey): List<SyncAuditEntry> =
        SkillSyncAuditStateMapper.entriesFor(storedState, key)

    override fun recentEntries(limit: Int): List<SyncAuditEntry> =
        SkillSyncAuditStateMapper.recentEntries(storedState, limit)

    @Synchronized
    override fun record(entry: SyncAuditEntry) {
        store.update { SkillSyncAuditStateMapper.withRecorded(it, entry) }
    }

    fun checkWritable() = store.checkWritable()

    fun storageStamp(): String = store.externalStamp()

    companion object {
        fun getInstance(): SkillSyncAuditStateService = service()
    }
}
