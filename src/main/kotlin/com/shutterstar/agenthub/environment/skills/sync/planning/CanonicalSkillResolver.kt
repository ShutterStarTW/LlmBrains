package com.shutterstar.agenthub.environment.skills.sync.planning

import com.shutterstar.agenthub.environment.skills.discovery.SkillFingerprint
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import java.nio.file.Path

/**
 * Bridges Milestone 2's [AgentSkill] (discovery-time aggregation) into the planning-time input
 * the sync planner needs. Reuses [SkillFingerprint]'s single-file `SKILL.md` convention so the
 * canonical fingerprint stays comparable with what [com.shutterstar.agenthub.environment.skills.discovery.SkillDiscoveryService]
 * already computed for consistency checks.
 */
internal class CanonicalSkillResolver(
    private val fingerprint: SkillFingerprint = SkillFingerprint(),
) {
    fun resolve(skill: AgentSkill): CanonicalSkillSource? {
        val sharedSource = skill.sources.firstOrNull { it.shared } ?: return null
        val canonicalPath = Path.of(sharedSource.path)
        return CanonicalSkillSource(
            skillId = skill.identity.id,
            canonicalPath = canonicalPath,
            scope = sharedSource.scope,
            fingerprint = fingerprint.calculate(canonicalPath),
        )
    }

}
