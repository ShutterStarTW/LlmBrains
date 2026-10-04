package com.shutterstar.agenthub.environment.skills.sync.migration

import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.projects.model.DiscoveredProject

/** Which agent owns the skill directory at a path, among the agents that discovery listed it under; null = nobody. */
internal typealias OwningAgent = (path: String, candidateAgentIds: List<String>, scope: SkillScope, project: DiscoveredProject?) -> String?

/**
 * The `ownerOf` the duplicate detectors take: asks [owning] about the agents discovery listed the
 * folder under. With [strict] a folder that is not the owner's own skill directory has no owner (it is
 * not a copy of that agent's, so cleaning it up is not that agent's business); otherwise the listing
 * agent is the fallback.
 */
internal fun duplicateOwner(
    owning: OwningAgent,
    project: DiscoveredProject?,
    strict: Boolean = false,
): (AgentSkill, SkillSource) -> String? = { skill, source ->
    val listedUnder = skill.sources.filter { it.path == source.path }.mapNotNull { it.agentId }.distinct()
    owning(source.path, listedUnder, source.scope, project) ?: source.agentId.takeUnless { strict }
}
