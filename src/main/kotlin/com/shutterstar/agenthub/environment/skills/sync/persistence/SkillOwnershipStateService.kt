package com.shutterstar.agenthub.environment.skills.sync.persistence

import com.shutterstar.agenthub.storage.AgentHubStorage
import com.shutterstar.agenthub.storage.StateStore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import com.shutterstar.agenthub.environment.skills.sync.ownership.SyncOwnershipStore

/** Shared production state; standalone tests default to an injectable in-memory store. */
@Service(Service.Level.APP)
class SkillOwnershipStateService(
    private val store: StateStore<SkillOwnershipState> = AgentHubStorage.state("ownership", SkillOwnershipState::class.java, ::SkillOwnershipState),
    private val now: () -> Long = System::currentTimeMillis,
) : SyncOwnershipStore {
    private var storedState: SkillOwnershipState
        get() = store.snapshot()
        set(value) { store.update { value } }

    val state: SkillOwnershipState get() = storedState

    fun loadState(state: SkillOwnershipState) {
        this.storedState = state
    }

    override fun managedTarget(key: SkillInstanceKey, agentId: String): ManagedTarget? =
        SkillOwnershipStateMapper.managedTarget(storedState, key, agentId)

    override fun managedTargetsFor(key: SkillInstanceKey): List<ManagedTarget> =
        SkillOwnershipStateMapper.managedTargetsFor(storedState, key)

    @Synchronized
    override fun record(key: SkillInstanceKey, target: ManagedTarget) {
        store.update { original ->
            val updated = SkillOwnershipStateMapper.withRecorded(original, key, target)
            val entry = updated.entries.last()
            entry.recordedAtEpochMillis = now()
            updated.copy(removedEntries = original.removedEntries.filterNot { entryKey(it) == entryKey(entry) }.toMutableList())
        }
    }

    @Synchronized
    override fun remove(key: SkillInstanceKey, agentId: String) {
        store.update { previous -> withRemovalStamps(previous, SkillOwnershipStateMapper.withRemoved(previous, key, agentId)) }
    }

    override fun managedTarget(skillId: String, agentId: String): ManagedTarget? =
        SkillOwnershipStateMapper.managedTarget(storedState, skillId, agentId)

    override fun managedTargetsFor(skillId: String): List<ManagedTarget> =
        SkillOwnershipStateMapper.managedTargetsFor(storedState, skillId)

    @Synchronized
    override fun record(skillId: String, target: ManagedTarget) {
        record(SkillInstanceKey.legacy(skillId), target)
    }

    @Synchronized
    override fun remove(skillId: String, agentId: String) {
        store.update { previous -> withRemovalStamps(previous, SkillOwnershipStateMapper.withRemoved(previous, skillId, agentId)) }
    }

    private fun withRemovalStamps(previous: SkillOwnershipState, updated: SkillOwnershipState): SkillOwnershipState {
        val removed = previous.entries.filterNot { it in updated.entries }.map { it.copy(recordedAtEpochMillis = now()) }
        return updated.copy(removedEntries = (previous.removedEntries + removed).groupBy(::entryKey)
            .values.map { entries -> entries.maxBy { it.recordedAtEpochMillis } }.toMutableList())
    }

    private fun entryKey(entry: SkillOwnershipEntryState): List<String> = listOf(
        entry.runtimeId.ifBlank { "host" }, entry.scope.ifBlank { "GLOBAL" },
        SkillInstanceKey.normalizeContextPath(entry.contextPath), entry.skillId, entry.agentId,
    )

    fun checkWritable() = store.checkWritable()

    fun storageStamp(): String = store.externalStamp()

    companion object {
        fun getInstance(): SkillOwnershipStateService = service()
    }
}
