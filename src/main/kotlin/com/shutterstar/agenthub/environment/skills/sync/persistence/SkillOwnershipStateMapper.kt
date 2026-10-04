package com.shutterstar.agenthub.environment.skills.sync.persistence

import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import com.shutterstar.agenthub.environment.skills.model.SkillScope

object SkillOwnershipStateMapper {
    fun managedTarget(state: SkillOwnershipState, key: SkillInstanceKey, agentId: String): ManagedTarget? {
        if (state.schemaVersion != SkillOwnershipState.CURRENT_SCHEMA_VERSION) return null
        return state.entries
            .firstOrNull { entryMatches(it, key) && it.agentId == agentId }
            ?.let(::decodeEntry)
            ?: state.entries
                .firstOrNull { keyAllowsLegacy(key) && isLegacyEntry(it) && it.skillId == key.skillId && it.agentId == agentId }
            ?.let(::decodeEntry)
    }

    fun managedTargetsFor(state: SkillOwnershipState, key: SkillInstanceKey): List<ManagedTarget> {
        if (state.schemaVersion != SkillOwnershipState.CURRENT_SCHEMA_VERSION) return emptyList()
        val exact = state.entries.filter { entryMatches(it, key) }.mapNotNull(::decodeEntry)
        if (exact.isNotEmpty() || !keyAllowsLegacy(key)) return exact
        return state.entries.filter { isLegacyEntry(it) && it.skillId == key.skillId }.mapNotNull(::decodeEntry)
    }

    fun withRecorded(state: SkillOwnershipState, key: SkillInstanceKey, target: ManagedTarget): SkillOwnershipState {
        val remaining = state.entries.filterNot {
            it.agentId == target.agentId &&
                (entryMatches(it, key) || (keyAllowsLegacy(key) && isLegacyEntry(it) && it.skillId == key.skillId))
        }
        return state.copy(entries = (remaining + encodeEntry(key, target)).toMutableList())
    }

    fun withRemoved(state: SkillOwnershipState, key: SkillInstanceKey, agentId: String): SkillOwnershipState {
        val hasExact = state.entries.any { entryMatches(it, key) && it.agentId == agentId }
        val remaining = state.entries.filterNot {
            (entryMatches(it, key) || (!hasExact && keyAllowsLegacy(key) && isLegacyEntry(it) && it.skillId == key.skillId)) &&
                it.agentId == agentId
        }
        return state.copy(entries = remaining.toMutableList())
    }

    fun managedTarget(state: SkillOwnershipState, skillId: String, agentId: String): ManagedTarget? {
        if (state.schemaVersion != SkillOwnershipState.CURRENT_SCHEMA_VERSION) return null
        return state.entries
            .filter { it.skillId == skillId && it.agentId == agentId }
            .singleOrNull()
            ?.let(::decodeEntry)
    }

    fun managedTargetsFor(state: SkillOwnershipState, skillId: String): List<ManagedTarget> {
        if (state.schemaVersion != SkillOwnershipState.CURRENT_SCHEMA_VERSION) return emptyList()
        return state.entries.filter { it.skillId == skillId }.mapNotNull(::decodeEntry)
    }

    fun withRecorded(state: SkillOwnershipState, skillId: String, target: ManagedTarget): SkillOwnershipState {
        return withRecorded(state, SkillInstanceKey.legacy(skillId), target)
    }

    fun withRemoved(state: SkillOwnershipState, skillId: String, agentId: String): SkillOwnershipState {
        val matching = state.entries.filter { it.skillId == skillId && it.agentId == agentId }
        if (matching.size != 1) return state
        return state.copy(entries = state.entries.filterNot { it === matching.single() }.toMutableList())
    }

    private fun encodeEntry(key: SkillInstanceKey, target: ManagedTarget) = SkillOwnershipEntryState(
        skillId = key.skillId,
        runtimeId = key.runtimeId,
        scope = key.scope.name,
        contextPath = key.contextPath,
        agentId = target.agentId,
        path = target.path,
        requestedMode = target.requestedMode.name,
        effectiveMode = target.effectiveMode.name,
        lastFingerprint = target.lastFingerprint,
        operationId = target.operationId,
    )

    private fun entryMatches(entry: SkillOwnershipEntryState, key: SkillInstanceKey): Boolean {
        val entryKey = decodeKey(entry)
        return entryKey == key || (entryKey == null && key == SkillInstanceKey.legacy(entry.skillId))
    }

    private fun decodeKey(entry: SkillOwnershipEntryState): SkillInstanceKey? {
        if (entry.runtimeId.isBlank() || entry.scope.isBlank()) return null
        val scope = runCatching { SkillScope.valueOf(entry.scope) }.getOrNull()
            ?: return null
        return SkillInstanceKey(entry.runtimeId, scope, SkillInstanceKey.normalizeContextPath(entry.contextPath), entry.skillId)
    }

    private fun keyAllowsLegacy(key: SkillInstanceKey): Boolean =
        key.scope == SkillScope.GLOBAL

    private fun isLegacyEntry(entry: SkillOwnershipEntryState): Boolean {
        val decoded = decodeKey(entry)
        return decoded == null || decoded == SkillInstanceKey.legacy(entry.skillId)
    }

    private fun decodeEntry(entry: SkillOwnershipEntryState): ManagedTarget? {
        val requestedMode = runCatching { SkillSyncMode.valueOf(entry.requestedMode) }.getOrNull() ?: return null
        val effectiveMode = runCatching { EffectiveSyncMode.valueOf(entry.effectiveMode) }.getOrNull() ?: return null
        return ManagedTarget(
            agentId = entry.agentId,
            path = entry.path,
            requestedMode = requestedMode,
            effectiveMode = effectiveMode,
            lastFingerprint = entry.lastFingerprint,
            operationId = entry.operationId,
        )
    }
}
