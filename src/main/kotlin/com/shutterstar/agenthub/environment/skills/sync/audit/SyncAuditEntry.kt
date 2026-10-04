package com.shutterstar.agenthub.environment.skills.sync.audit

import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import java.time.Instant

data class SyncAuditEntry(
    val operationId: String,
    val timestamp: Instant,
    val skillId: String,
    val action: SyncAction,
    val affectedAgents: Set<String>,
    val result: SyncOperationStatus,
    val instanceKey: SkillInstanceKey? = null,
)
