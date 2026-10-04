package com.shutterstar.agenthub.environment.skills.sync.migration

import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncRequest

/**
 * One [PromoteSkill][SkillSyncRequest.PromoteSkill] followed by a
 * [ShareSkill][SkillSyncRequest.ShareSkill] per remaining source — the sequence that would turn an
 * already-duplicated skill into a properly shared one. Each request still goes through the normal
 * [com.shutterstar.agenthub.environment.skills.sync.SkillSyncService] plan/execute path, so all
 * existing safety machinery (revalidation, ownership, audit) applies unchanged.
 */
data class BulkMigrationCandidate(
    val skill: AgentSkill,
    val requests: List<SkillSyncRequest>,
)
