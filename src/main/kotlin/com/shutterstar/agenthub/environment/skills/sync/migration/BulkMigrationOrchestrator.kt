package com.shutterstar.agenthub.environment.skills.sync.migration

import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.SkillSyncApplicationService
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncRequest
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import com.shutterstar.agenthub.projects.model.DiscoveredProject

internal data class BulkShareOutcome(val agentId: String, val succeeded: Boolean, val message: String?)

internal data class BulkMigrationOutcome(
    val skillId: String,
    val skillName: String,
    val promoteSucceeded: Boolean,
    val promoteMessage: String?,
    val shareResults: List<BulkShareOutcome>,
)

/**
 * Actually runs what [BulkMigrationDetector] only proposes: promote, then rediscover before
 * sharing (a [BulkMigrationCandidate]'s `AgentSkill` predates its own promotion — none of its
 * sources are `shared` yet, so planning a `ShareSkill` against the *stale* skill object would
 * fail canonical resolution outright), then a fresh per-target share plan for each remaining
 * source. Each candidate is fully isolated: one skill's promote or share failure never stops the
 * rest, matching [BulkMigrationCandidate]'s own doc comment that every request still goes through
 * the normal plan/execute path with all its existing safety machinery.
 */
internal class BulkMigrationOrchestrator(
    private val service: SkillSyncApplicationService,
    private val rediscover: (SkillScope, DiscoveredProject?) -> List<AgentSkill>,
) {
    fun migrate(
        candidates: List<BulkMigrationCandidate>,
        scope: SkillScope,
        project: DiscoveredProject?,
    ): List<BulkMigrationOutcome> = candidates.map { migrateOne(it, scope, project) }

    private fun migrateOne(
        candidate: BulkMigrationCandidate,
        scope: SkillScope,
        project: DiscoveredProject?,
    ): BulkMigrationOutcome {
        val skillId = candidate.skill.identity.id
        val skillName = candidate.skill.name
        val promoteRequest = candidate.requests.firstOrNull() as? SkillSyncRequest.PromoteSkill
            ?: return BulkMigrationOutcome(skillId, skillName, promoteSucceeded = false, "No promotion request in this candidate.", emptyList())

        val promotePrepared = service.preparePromote(candidate.skill, promoteRequest.sourceAgentId, promoteRequest.sourcePath, scope, project)
        val promoteResult = service.execute(promotePrepared)
        if (promoteResult.status != SyncOperationStatus.SUCCESS) {
            val message = promoteResult.errors.joinToString { it.message }.takeIf(String::isNotBlank)
                ?: promotePrepared.planResult.plan.warnings.joinToString { it.message }.takeIf(String::isNotBlank)
                ?: "Promote did not complete."
            return BulkMigrationOutcome(skillId, skillName, promoteSucceeded = false, message, emptyList())
        }

        val shareRequests = candidate.requests.filterIsInstance<SkillSyncRequest.ShareSkill>()
        if (shareRequests.isEmpty()) {
            return BulkMigrationOutcome(skillId, skillName, promoteSucceeded = true, promoteMessage = null, shareResults = emptyList())
        }

        val freshSkill = rediscover(scope, project).firstOrNull { it.identity.id == skillId }
            ?: return BulkMigrationOutcome(
                skillId,
                skillName,
                promoteSucceeded = true,
                promoteMessage = "Promoted, but the skill could not be rediscovered to share it further; use Share with… manually.",
                shareResults = emptyList(),
            )

        val shareResults = shareRequests.map { request ->
            val prepared = service.prepareShare(freshSkill, request.targetAgentId, scope, project)
            val result = service.execute(prepared)
            val succeeded = result.status == SyncOperationStatus.SUCCESS || result.status == SyncOperationStatus.PARTIAL_SUCCESS
            BulkShareOutcome(
                request.targetAgentId,
                succeeded,
                result.errors.joinToString { it.message }.takeIf { !succeeded && it.isNotBlank() },
            )
        }

        return BulkMigrationOutcome(skillId, skillName, promoteSucceeded = true, promoteMessage = null, shareResults = shareResults)
    }
}
