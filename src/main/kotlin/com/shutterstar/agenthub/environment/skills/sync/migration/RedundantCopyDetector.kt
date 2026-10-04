package com.shutterstar.agenthub.environment.skills.sync.migration

import com.shutterstar.agenthub.environment.capabilities.AgentCapabilityRegistry
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillSource

/**
 * An already-shared [skill] that some agents keep their own link or copy of although they read the
 * shared folder directly - [agentIds] are the agents whose own entry is redundant, [linkedAgentIds]
 * the ones among them where that entry is a link (symlink/junction) rather than a real copy.
 */
data class RedundantCopyCandidate(
    val skill: AgentSkill,
    val agentIds: List<String>,
    val linkedAgentIds: Set<String> = emptySet(),
)

/**
 * Pure scan for the other kind of duplicate: the skill is shared, and an agent that reads the shared
 * folder directly (see [AgentCapabilityRegistry.agentIdsSupportingSharedSkills]) still has the same
 * skill in its own directory, as a link or as an identical copy. Only content identical to the shared
 * source qualifies - a differing copy is a conflict, never cleanup - and vendor-provided copies and
 * agents that are not installed never do. No I/O of its own: whether an agent owns a directory is
 * decided by [ownerOf], whether it is installed by [isInstalled], whether an entry is a link by [isLink].
 */
class RedundantCopyDetector(
    private val isInstalled: (String) -> Boolean = { true },
    private val ownerOf: (AgentSkill, SkillSource) -> String? = { _, source -> source.agentId },
    private val readsSharedFolder: (String) -> Boolean = { it in AgentCapabilityRegistry.agentIdsSupportingSharedSkills() },
    private val isLink: (SkillSource) -> Boolean = { false },
) {
    fun detect(skills: List<AgentSkill>): List<RedundantCopyCandidate> = skills.mapNotNull(::candidateFor)

    private fun candidateFor(skill: AgentSkill): RedundantCopyCandidate? {
        val shared = skill.sources.firstOrNull { it.shared } ?: return null
        val entries = skill.sources
            .filter { !it.shared && !it.system && it.fingerprint == shared.fingerprint }
            .mapNotNull { source -> ownerOf(skill, source)?.takeIf { isInstalled(it) && readsSharedFolder(it) }?.let { it to source } }
        if (entries.isEmpty()) return null
        val agentIds = entries.map { (agentId, _) -> agentId }.distinct().sorted()
        val linked = agentIds.filterTo(linkedSetOf()) { agentId -> entries.filter { it.first == agentId }.all { (_, source) -> isLink(source) } }
        return RedundantCopyCandidate(skill, agentIds, linked)
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
)
