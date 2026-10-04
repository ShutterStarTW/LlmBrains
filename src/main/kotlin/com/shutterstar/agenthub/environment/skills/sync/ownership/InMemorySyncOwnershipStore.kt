package com.shutterstar.agenthub.environment.skills.sync.ownership

import java.util.concurrent.ConcurrentHashMap
import com.shutterstar.agenthub.environment.skills.model.SkillScope

/**
 * Lightweight implementation used by pure-JDK callers and tests. Production callers can inject
 * the `PersistentStateComponent`-backed store from `sync/persistence` when durability is needed.
 */
class InMemorySyncOwnershipStore : SyncOwnershipStore {
    private val entries = ConcurrentHashMap<Pair<SkillInstanceKey, String>, ManagedTarget>()

    override fun managedTarget(key: SkillInstanceKey, agentId: String): ManagedTarget? =
        entries[key to agentId] ?: key.takeIf { it.scope == SkillScope.GLOBAL }
            ?.let { entries[SkillInstanceKey.legacy(it.skillId) to agentId] }

    override fun managedTargetsFor(key: SkillInstanceKey): List<ManagedTarget> {
        val exact = entries.filterKeys { it.first == key }.values.toList()
        if (exact.isNotEmpty() || key.scope != SkillScope.GLOBAL) return exact
        return entries.filterKeys { it.first == SkillInstanceKey.legacy(key.skillId) }.values.toList()
    }

    override fun record(key: SkillInstanceKey, target: ManagedTarget) {
        if (key.scope == SkillScope.GLOBAL && key != SkillInstanceKey.legacy(key.skillId)) {
            entries.remove(SkillInstanceKey.legacy(key.skillId) to target.agentId)
        }
        entries[key to target.agentId] = target
    }

    override fun remove(key: SkillInstanceKey, agentId: String) {
        val removed = entries.remove(key to agentId)
        if (removed == null && key.scope == SkillScope.GLOBAL) {
            entries.remove(SkillInstanceKey.legacy(key.skillId) to agentId)
        }
    }

    override fun managedTarget(skillId: String, agentId: String): ManagedTarget? = entries
        .filterKeys { (key, storedAgentId) -> key.skillId == skillId && storedAgentId == agentId }
        .values.singleOrNull()

    override fun managedTargetsFor(skillId: String): List<ManagedTarget> =
        entries.filterKeys { it.first.skillId == skillId }.values.toList()

    override fun record(skillId: String, target: ManagedTarget) {
        record(SkillInstanceKey.legacy(skillId), target)
    }

    override fun remove(skillId: String, agentId: String) {
        val matchingKeys = entries.keys.filter { (key, storedAgentId) -> key.skillId == skillId && storedAgentId == agentId }
        if (matchingKeys.size == 1) entries.remove(matchingKeys.single())
    }
}
