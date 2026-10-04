package com.shutterstar.agenthub.environment.skills.sync.persistence

import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAction
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAuditEntry
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import java.time.Instant
import com.shutterstar.agenthub.environment.skills.model.SkillScope

object SkillSyncAuditStateMapper {
    fun entriesFor(state: SkillSyncAuditState, skillId: String): List<SyncAuditEntry> {
        if (state.schemaVersion != SkillSyncAuditState.CURRENT_SCHEMA_VERSION) return emptyList()
        return state.entries
            .filter { it.skillId == skillId }
            .mapNotNull(::decodeEntry)
            .sortedByDescending { it.timestamp }
    }

    fun entriesFor(state: SkillSyncAuditState, key: SkillInstanceKey): List<SyncAuditEntry> {
        if (state.schemaVersion != SkillSyncAuditState.CURRENT_SCHEMA_VERSION) return emptyList()
        return state.entries.mapNotNull(::decodeEntry)
            .filter { it.instanceKey == key }
            .sortedByDescending { it.timestamp }
    }

    fun recentEntries(state: SkillSyncAuditState, limit: Int): List<SyncAuditEntry> {
        if (state.schemaVersion != SkillSyncAuditState.CURRENT_SCHEMA_VERSION) return emptyList()
        return state.entries
            .mapNotNull(::decodeEntry)
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    /** Appends (never replaces, unlike ownership — an audit trail is a log). Caps at [SkillSyncAuditState.MAX_ENTRIES], dropping the oldest. */
    fun withRecorded(state: SkillSyncAuditState, entry: SyncAuditEntry): SkillSyncAuditState {
        val appended = state.entries + encodeEntry(entry)
        val bounded = appended
            .sortedBy { it.timestampEpochMillis }
            .takeLast(SkillSyncAuditState.MAX_ENTRIES)
        return state.copy(entries = bounded.toMutableList())
    }

    private fun encodeEntry(entry: SyncAuditEntry) = SkillSyncAuditEntryState(
        operationId = entry.operationId,
        timestampEpochMillis = entry.timestamp.toEpochMilli(),
        skillId = entry.skillId,
        runtimeId = entry.instanceKey?.runtimeId.orEmpty(),
        scope = entry.instanceKey?.scope?.name.orEmpty(),
        contextPath = entry.instanceKey?.contextPath.orEmpty(),
        action = entry.action.name,
        affectedAgents = entry.affectedAgents.toMutableList(),
        result = entry.result.name,
    )

    private fun decodeEntry(entry: SkillSyncAuditEntryState): SyncAuditEntry? {
        val action = runCatching { SyncAction.valueOf(entry.action) }.getOrNull() ?: return null
        val result = runCatching { SyncOperationStatus.valueOf(entry.result) }.getOrNull() ?: return null
        val instanceKey = if (entry.runtimeId.isNotBlank() && entry.scope.isNotBlank()) {
            val scope = runCatching { SkillScope.valueOf(entry.scope) }.getOrNull()
                ?: return null
            SkillInstanceKey(entry.runtimeId, scope, SkillInstanceKey.normalizeContextPath(entry.contextPath), entry.skillId)
        } else {
            null
        }
        return SyncAuditEntry(
            operationId = entry.operationId,
            timestamp = Instant.ofEpochMilli(entry.timestampEpochMillis),
            skillId = entry.skillId,
            action = action,
            affectedAgents = entry.affectedAgents.toSet(),
            result = result,
            instanceKey = instanceKey,
        )
    }
}
