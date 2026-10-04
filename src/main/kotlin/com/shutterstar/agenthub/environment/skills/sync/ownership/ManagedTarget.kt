package com.shutterstar.agenthub.environment.skills.sync.ownership

import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode

data class ManagedTarget(
    val agentId: String,
    val path: String,
    val requestedMode: SkillSyncMode,
    val effectiveMode: EffectiveSyncMode,
    val lastFingerprint: String?,
    val operationId: String? = null,
)
