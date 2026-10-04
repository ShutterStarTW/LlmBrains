package com.shutterstar.agenthub.environment.skills.sync.migration

import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class BulkMigrationDetectorTest {
    private val detector = BulkMigrationDetector()

    @Test
    fun `a 2-source IDENTICAL skill produces one PromoteSkill and one ShareSkill`() {
        val skill = skill(
            id = "skill-1",
            consistency = SkillConsistency.IDENTICAL,
            sources = listOf(source("claude", "/claude/php-review"), source("codex", "/codex/php-review")),
        )

        val candidates = detector.detect(listOf(skill))

        val candidate = candidates.single()
        assertEquals(skill, candidate.skill)
        assertEquals(2, candidate.requests.size)
        val promote = candidate.requests[0] as SkillSyncRequest.PromoteSkill
        assertEquals("claude", promote.sourceAgentId)
        assertEquals(Path.of("/claude/php-review"), promote.sourcePath)
        val share = candidate.requests[1] as SkillSyncRequest.ShareSkill
        assertEquals("codex", share.targetAgentId)
    }

    @Test
    fun `a 3-source IDENTICAL skill promotes the alphabetically-first agent and shares with the rest`() {
        val skill = skill(
            id = "skill-1",
            consistency = SkillConsistency.IDENTICAL,
            sources = listOf(source("codex", "/codex/x"), source("amp", "/amp/x"), source("claude", "/claude/x")),
        )

        val candidate = detector.detect(listOf(skill)).single()

        assertEquals(3, candidate.requests.size)
        val promote = candidate.requests[0] as SkillSyncRequest.PromoteSkill
        assertEquals("amp", promote.sourceAgentId)
        val shareTargets = candidate.requests.drop(1).map { (it as SkillSyncRequest.ShareSkill).targetAgentId }
        assertEquals(listOf("claude", "codex"), shareTargets)
    }

    @Test
    fun `a DIFFERENT-consistency skill is never proposed for bulk migration`() {
        val skill = skill(
            id = "skill-1",
            consistency = SkillConsistency.DIFFERENT,
            sources = listOf(source("claude", "/claude/x"), source("codex", "/codex/x")),
        )

        assertTrue(detector.detect(listOf(skill)).isEmpty())
    }

    @Test
    fun `a SINGLE_SOURCE skill is never proposed for bulk migration`() {
        val skill = skill(
            id = "skill-1",
            consistency = SkillConsistency.SINGLE_SOURCE,
            sources = listOf(source("claude", "/claude/x")),
        )

        assertTrue(detector.detect(listOf(skill)).isEmpty())
    }

    @Test
    fun `a skill that already has a shared source is excluded, even if otherwise IDENTICAL`() {
        val skill = skill(
            id = "skill-1",
            consistency = SkillConsistency.IDENTICAL,
            sources = listOf(
                source("claude", "/claude/x"),
                source("codex", "/codex/x"),
                SkillSource(agentId = null, path = "/shared/x", scope = SkillScope.GLOBAL, shared = true, fingerprint = "fixture"),
            ),
        )

        assertTrue(detector.detect(listOf(skill)).isEmpty())
    }

    @Test
    fun `a source with a null agentId does not crash and is excluded from candidacy`() {
        val skill = skill(
            id = "skill-1",
            consistency = SkillConsistency.IDENTICAL,
            sources = listOf(
                source("claude", "/claude/x"),
                SkillSource(agentId = null, path = "/unowned/x", scope = SkillScope.GLOBAL, shared = false, fingerprint = "fixture"),
            ),
        )

        // Only one source has a non-null agentId, so there are fewer than 2 eligible sources.
        assertTrue(detector.detect(listOf(skill)).isEmpty())
    }

    @Test
    fun `multiple independent skills each produce their own candidate without interfering`() {
        val eligible = skill(
            id = "skill-1",
            consistency = SkillConsistency.IDENTICAL,
            sources = listOf(source("claude", "/claude/a"), source("codex", "/codex/a")),
        )
        val ineligible = skill(
            id = "skill-2",
            consistency = SkillConsistency.DIFFERENT,
            sources = listOf(source("claude", "/claude/b"), source("codex", "/codex/b")),
        )

        val candidates = detector.detect(listOf(eligible, ineligible))

        assertEquals(1, candidates.size)
        assertEquals("skill-1", candidates.single().skill.identity.id)
    }

    @Test
    fun `agents that are not installed are not migrated`() {
        val skill = skill(
            id = "skill-1",
            consistency = SkillConsistency.IDENTICAL,
            sources = listOf(source("amp", "/amp/x"), source("claude", "/claude/x"), source("codex", "/codex/x")),
        )

        val candidate = BulkMigrationDetector(isInstalled = { it != "amp" }).detect(listOf(skill)).single()

        assertEquals("claude", (candidate.requests[0] as SkillSyncRequest.PromoteSkill).sourceAgentId)
        assertEquals(listOf("codex"), candidate.requests.drop(1).map { (it as SkillSyncRequest.ShareSkill).targetAgentId })
        assertTrue(BulkMigrationDetector(isInstalled = { it == "claude" }).detect(listOf(skill)).isEmpty())
    }

    @Test
    fun `one folder listed under several agents is a single copy`() {
        val skill = skill(
            id = "skill-1",
            consistency = SkillConsistency.IDENTICAL,
            sources = listOf(source("claude", "/home/.claude/skills/x"), source("cursor", "/home/.claude/skills/x")),
        )

        assertTrue(detector.detect(listOf(skill)).isEmpty())
    }

    @Test
    fun `an agent's own copy plus its vendor-synced copy is still a duplicate that can be promoted`() {
        val synced = source("claude", "/home/.claude/skills/synced/x/url-checker").copy(system = true)
        val skill = skill(
            id = "skill-1",
            consistency = SkillConsistency.IDENTICAL,
            sources = listOf(source("claude", "/home/.claude/skills/url-checker"), synced),
        )

        val candidate = detector.detect(listOf(skill)).single()

        val promote = candidate.requests.single() as SkillSyncRequest.PromoteSkill
        assertEquals(Path.of("/home/.claude/skills/url-checker"), promote.sourcePath)
    }

    @Test
    fun `a vendor-provided copy is promoted only when nothing else can be`() {
        val system = source("amp", "/amp/x").copy(system = true)
        val skill = skill(
            id = "skill-1",
            consistency = SkillConsistency.IDENTICAL,
            sources = listOf(system, source("codex", "/codex/x")),
        )

        val promote = detector.detect(listOf(skill)).single().requests[0] as SkillSyncRequest.PromoteSkill

        assertEquals("codex", promote.sourceAgentId)
    }

    private fun skill(id: String, consistency: SkillConsistency, sources: List<SkillSource>): AgentSkill = AgentSkill(
        identity = SkillIdentity(id),
        name = "name-$id",
        description = null,
        scope = SkillScope.GLOBAL,
        sources = sources,
        compatibleAgents = emptySet(),
        consistency = consistency,
    )

    private fun source(agentId: String, path: String): SkillSource =
        SkillSource(agentId = agentId, path = path, scope = SkillScope.GLOBAL, shared = false, fingerprint = "fixture")
}
