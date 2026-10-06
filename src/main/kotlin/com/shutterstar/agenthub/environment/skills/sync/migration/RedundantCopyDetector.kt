package com.shutterstar.agenthub.environment.skills.sync.migration

import com.shutterstar.agenthub.environment.capabilities.AgentCapabilityRegistry
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillSource

/**
 * An already-shared [skill] whose per-agent entries could go. [agentIds] are the agents that read the shared
 * folder directly but keep their own entry (the entry is redundant), [linkedAgentIds] the ones among them where
 * that entry is a link (symlink/junction) rather than a real copy. [convertAgentIds] are agents that cannot read
 * the shared folder and keep an identical real copy: their copy is replaced by a link to the shared skill.
 */
data class RedundantCopyCandidate(
    val skill: AgentSkill,
    val agentIds: List<String>,
    val linkedAgentIds: Set<String> = emptySet(),
    val convertAgentIds: List<String> = emptyList(),
)

/**
 * Pure scan for the other kind of duplicate: the skill is shared, and an agent that reads the shared
 * folder directly (see [AgentCapabilityRegistry.agentIdsSupportingSharedSkills]) still has the same
 * skill in its own directory, as a link or as an identical copy. An agent that cannot read the shared folder
 * and keeps an identical real copy qualifies as well when it can be given a link instead ([canLink]).
 * Only content identical to the shared source qualifies - a differing copy is a conflict, never cleanup - and
 * vendor-provided copies and agents that are not installed never do. No I/O of its own: whether an agent owns a
 * directory is decided by [ownerOf], whether it is installed by [isInstalled], whether an entry is a link by [isLink].
 */
class RedundantCopyDetector(
    private val isInstalled: (String) -> Boolean = { true },
    private val ownerOf: (AgentSkill, SkillSource) -> String? = { _, source -> source.agentId },
    private val readsSharedFolder: (String) -> Boolean = { it in AgentCapabilityRegistry.agentIdsSupportingSharedSkills() },
    private val isLink: (SkillSource) -> Boolean = { false },
    private val canLink: (String) -> Boolean = { false },
) {
    fun detect(skills: List<AgentSkill>): List<RedundantCopyCandidate> = skills.mapNotNull(::candidateFor)

    private fun candidateFor(skill: AgentSkill): RedundantCopyCandidate? {
        val shared = skill.sources.firstOrNull { it.shared } ?: return null
        val identical = skill.sources
            .filter { !it.shared && !it.system && it.fingerprint == shared.fingerprint }
            .mapNotNull { source -> ownerOf(skill, source)?.takeIf(isInstalled)?.let { it to source } }
        val entries = identical.filter { (agentId, _) -> readsSharedFolder(agentId) }
        val convert = identical
            .filter { (agentId, source) -> !readsSharedFolder(agentId) && canLink(agentId) && !isLink(source) }
            .map { (agentId, _) -> agentId }.distinct().sorted()
        if (entries.isEmpty() && convert.isEmpty()) return null
        val agentIds = entries.map { (agentId, _) -> agentId }.distinct().sorted()
        val linked = agentIds.filterTo(linkedSetOf()) { agentId -> entries.filter { it.first == agentId }.all { (_, source) -> isLink(source) } }
        return RedundantCopyCandidate(skill, agentIds, linked, convert)
    }
}

/** What cleaning up one [RedundantCopyCandidate] did, per agent. */
data class RedundantCopyOutcome(
    val skillId: String,
    val skillName: String,
    val removed: List<String>,
    /** Agents where the plan found nothing to remove, with why (a differing copy, a shared folder, …). */
    val skipped: Map<String, String>,
    val failed: Map<String, String>,
    /** Agents whose identical copy was replaced by a link. */
    val converted: List<String> = emptyList(),
)
