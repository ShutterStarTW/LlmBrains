package com.shutterstar.agenthub.environment.skills.sync.audit

import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import java.util.concurrent.CopyOnWriteArrayList

class InMemorySyncAuditTrail : SyncAuditTrail {
    private val entries = CopyOnWriteArrayList<SyncAuditEntry>()

    override fun record(entry: SyncAuditEntry) {
        entries += entry
    }

    override fun entriesFor(skillId: String): List<SyncAuditEntry> =
        entries.filter { it.skillId == skillId }.sortedByDescending { it.timestamp }

    override fun entriesFor(key: SkillInstanceKey): List<SyncAuditEntry> =
        entries.filter { it.instanceKey == key }.sortedByDescending { it.timestamp }

    override fun recentEntries(limit: Int): List<SyncAuditEntry> =
        entries.sortedByDescending { it.timestamp }.take(limit)
}
