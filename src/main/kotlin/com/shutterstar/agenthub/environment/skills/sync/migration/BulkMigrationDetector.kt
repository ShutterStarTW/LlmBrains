package com.shutterstar.agenthub.environment.skills.sync.migration

import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncRequest
import java.nio.file.Path

/**
 * Pure discovery-side scan for skills already duplicated identically across 2+ agents but never
 * promoted to a shared location (spec §78-82) — no execution, no side effects. Only
 * [SkillConsistency.IDENTICAL] skills are eligible; `DIFFERENT` content is never auto-migrated
 * (same "never touch DIFFERENT" policy enforced elsewhere, spec §27/§35).
 *
 * Only installed agents count ([isInstalled]), and [ownerOf] names the agent a copy really belongs
 * to: several agents scan each other's folders, so one directory may be listed under all of them and
 * is still a single copy. Two distinct copies are enough (even of one agent, e.g. its own plus a vendor-synced one):
 * the skill is then promoted, and shared with whichever other agents have a copy.
 */
class BulkMigrationDetector(
    private val isInstalled: (String) -> Boolean = { true },
    private val ownerOf: (AgentSkill, SkillSource) -> String? = { _, source -> source.agentId },
) {
    fun detect(skills: List<AgentSkill>): List<BulkMigrationCandidate> = skills.mapNotNull(::candidateFor)

    private fun candidateFor(skill: AgentSkill): BulkMigrationCandidate? {
        if (skill.consistency != SkillConsistency.IDENTICAL) return null
        if (skill.sources.any { it.shared }) return null

        // A vendor-provided copy is promoted only when nothing else can be (the bulk dialog does not warn per skill).
        val copies = skill.sources
            .mapNotNull { source -> ownerOf(skill, source)?.takeIf(isInstalled)?.let { source to it } }
            .distinctBy { (source, _) -> source.path }
            .sortedWith(compareBy({ (source, _) -> source.system }, { (_, agentId) -> agentId }))
        if (copies.size < 2) return null

        val (promotionSource, promotionAgentId) = copies.first()
        val shareTargets = copies.drop(1).map { (_, agentId) -> agentId }.distinct().filter { it != promotionAgentId }

        val requests: List<SkillSyncRequest> = buildList {
            add(
                SkillSyncRequest.PromoteSkill(
                    skillId = skill.identity.id,
                    sourceAgentId = promotionAgentId,
                    sourcePath = Path.of(promotionSource.path),
                    scope = promotionSource.scope,
                ),
            )
            shareTargets.forEach { agentId ->
                add(SkillSyncRequest.ShareSkill(skillId = skill.identity.id, targetAgentId = agentId))
            }
        }
        return BulkMigrationCandidate(skill, requests)
    }
}
