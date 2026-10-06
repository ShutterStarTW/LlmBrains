package com.shutterstar.agenthub.environment.skills.sync.migration

import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RedundantCopyDetectorTest {
    @Test
    fun `an agent that reads the shared folder and keeps an identical copy is a candidate`() {
        val candidate = RedundantCopyDetector().detect(listOf(skill(shared("fp"), agent("codex", "fp")))).single()

        assertEquals(listOf("codex"), candidate.agentIds)
    }

    @Test
    fun `a link is told apart from a real copy`() {
        val skill = skill(shared("fp"), agent("codex", "fp"), agent("cursor", "fp"))

        val candidate = RedundantCopyDetector(isLink = { it.agentId == "codex" }).detect(listOf(skill)).single()

        assertEquals(listOf("codex", "cursor"), candidate.agentIds)
        assertEquals(setOf("codex"), candidate.linkedAgentIds)
    }

    @Test
    fun `an agent that does not read the shared folder is never a candidate`() {
        assertTrue(RedundantCopyDetector().detect(listOf(skill(shared("fp"), agent("claude", "fp")))).isEmpty())
    }

    @Test
    fun `an identical real copy of an agent that cannot read the shared folder is swapped for a link when links are possible`() {
        val skill = skill(shared("fp"), agent("claude", "fp"), agent("codex", "fp"))

        val candidate = RedundantCopyDetector(canLink = { true }).detect(listOf(skill)).single()

        assertEquals(listOf("claude"), candidate.convertAgentIds)
        assertEquals(listOf("codex"), candidate.agentIds)
        // Without link support (copy mode, no adapter) there is nothing to swap.
        assertEquals(emptyList<String>(), RedundantCopyDetector().detect(listOf(skill)).single().convertAgentIds)
    }

    @Test
    fun `a link or a differing copy of an agent that cannot read the shared folder is left alone`() {
        val linked = skill(shared("fp"), agent("claude", "fp"))
        val differing = skill(shared("fp"), agent("claude", "other"))

        assertTrue(RedundantCopyDetector(canLink = { true }, isLink = { true }).detect(listOf(linked)).isEmpty())
        assertTrue(RedundantCopyDetector(canLink = { true }).detect(listOf(differing)).isEmpty())
    }

    @Test
    fun `a differing copy is a conflict, not clean-up`() {
        assertTrue(RedundantCopyDetector().detect(listOf(skill(shared("fp"), agent("codex", "other")))).isEmpty())
    }

    @Test
    fun `a vendor-provided copy is never a candidate`() {
        assertTrue(RedundantCopyDetector().detect(listOf(skill(shared("fp"), agent("codex", "fp", system = true)))).isEmpty())
    }

    @Test
    fun `a skill that is not shared has nothing to clean up`() {
        assertTrue(RedundantCopyDetector().detect(listOf(skill(agent("codex", "fp"), agent("cursor", "fp")))).isEmpty())
    }

    @Test
    fun `only installed agents are listed`() {
        val skill = skill(shared("fp"), agent("codex", "fp"), agent("cursor", "fp"))

        val candidate = RedundantCopyDetector(isInstalled = { it == "cursor" }).detect(listOf(skill)).single()

        assertEquals(listOf("cursor"), candidate.agentIds)
        assertTrue(RedundantCopyDetector(isInstalled = { false }).detect(listOf(skill)).isEmpty())
    }

    @Test
    fun `the owner of a folder decides whose copy it is`() {
        val skill = skill(shared("fp"), agent("cursor", "fp", path = "/home/.claude/skills/x"))

        // Cursor merely scans Claude's folder: its owner is claude, which reads no shared folder.
        assertTrue(RedundantCopyDetector(ownerOf = { _, _ -> "claude" }).detect(listOf(skill)).isEmpty())
        // No owner at all (not the agent's own directory) is not a candidate either.
        assertTrue(RedundantCopyDetector(ownerOf = { _, _ -> null }).detect(listOf(skill)).isEmpty())
    }

    private fun skill(vararg sources: SkillSource) = AgentSkill(
        SkillIdentity("x"),
        "x",
        null,
        SkillScope.GLOBAL,
        sources.toList(),
        emptySet(),
        SkillConsistency.IDENTICAL,
    )

    private fun shared(fingerprint: String) =
        SkillSource(null, "/home/.agents/skills/x", SkillScope.GLOBAL, shared = true, fingerprint = fingerprint)

    private fun agent(agentId: String, fingerprint: String, system: Boolean = false, path: String = "/home/.$agentId/skills/x") =
        SkillSource(agentId, path, SkillScope.GLOBAL, shared = false, fingerprint = fingerprint, system = system)
}
