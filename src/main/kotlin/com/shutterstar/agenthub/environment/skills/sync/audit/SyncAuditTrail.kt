package com.shutterstar.agenthub.environment.skills.sync.audit

import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey

interface SyncAuditTrail {
    fun record(entry: SyncAuditEntry)

    fun entriesFor(skillId: String): List<SyncAuditEntry>

    fun entriesFor(key: SkillInstanceKey): List<SyncAuditEntry>

    fun recentEntries(limit: Int = 50): List<SyncAuditEntry>
}
