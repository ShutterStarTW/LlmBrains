package com.shutterstar.agenthub.environment.skills.sync.ownership

interface SyncOwnershipStore {
    fun managedTarget(key: SkillInstanceKey, agentId: String): ManagedTarget?

    fun managedTargetsFor(key: SkillInstanceKey): List<ManagedTarget>

    fun record(key: SkillInstanceKey, target: ManagedTarget)

    fun remove(key: SkillInstanceKey, agentId: String)

    /** Compatibility lookup. Returns null when multiple physical contexts would be ambiguous. */
    fun managedTarget(skillId: String, agentId: String): ManagedTarget?

    fun managedTargetsFor(skillId: String): List<ManagedTarget>

    fun record(skillId: String, target: ManagedTarget)

    fun remove(skillId: String, agentId: String)
}
