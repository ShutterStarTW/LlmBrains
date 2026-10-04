package com.shutterstar.agenthub.environment.skills.ui

import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.environment.skills.sync.migration.BulkMigrationDetector
import com.shutterstar.agenthub.environment.skills.sync.migration.RedundantCopyCandidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SkillBulkPlanModelTest {
    private fun source(agent: String?, path: String, shared: Boolean = false, system: Boolean = false) =
        SkillSource(agent, path, SkillScope.GLOBAL, shared, "fp", system = system)

    private fun skill(name: String, vararg sources: SkillSource, consistency: SkillConsistency = SkillConsistency.IDENTICAL) =
        AgentSkill(SkillIdentity(name), name, null, SkillScope.GLOBAL, sources.toList(), emptySet(), consistency)

    @Test
    fun `migration plan is grouped by directory and lists the skills per directory`() {
        val skills = listOf(
            skill("alpha", source("claude", "/home/u/.claude/skills/alpha"), source("codex", "/home/u/.codex/skills/alpha")),
            skill("beta", source("claude", "/home/u/.claude/skills/beta"), source("codex", "/home/u/.codex/skills/beta")),
        )
        val candidates = BulkMigrationDetector().detect(skills)
        val plan = SkillBulkPlanModel.migration(candidates, Path.of("/home/u/.agents/skills"), Path.of("/backups")) { it }

        val byChange = plan.directories.groupBy { it.change }
        val received = byChange.getValue(BulkPlanChange.RECEIVES_SHARED).single()
        assertEquals(listOf("alpha", "beta"), received.skills)
        assertEquals(Path.of("/home/u/.agents/skills").toString(), received.path)
        val moved = byChange.getValue(BulkPlanChange.MOVED_TO_SHARED).single()
        assertEquals(Path.of("/home/u/.claude/skills").toString(), moved.path)
        assertEquals(listOf("claude"), moved.agentIds)
        assertEquals(listOf("alpha", "beta"), moved.skills)
        val linked = byChange.getValue(BulkPlanChange.LINKED).single()
        assertEquals(Path.of("/home/u/.codex/skills").toString(), linked.path)
        assertEquals("Migrate 2 Skills", plan.applyLabel)
        assertEquals(Path.of("/backups").toString(), plan.backupPath)
    }

    @Test
    fun `a vendor provided copy in the migration is called out as a warning`() {
        val skills = listOf(skill("alpha", source("claude", "/c/alpha"), source("codex", "/x/alpha", system = true)))
        val plan = SkillBulkPlanModel.migration(BulkMigrationDetector().detect(skills), null, null) { it.uppercase() }
        assertTrue(plan.warnings.single().startsWith("alpha: includes a vendor-provided copy (CODEX)"), plan.warnings.toString())
    }

    @Test
    fun `cleanup plan separates removed links from removed copies by directory`() {
        val shared = skill(
            "alpha",
            source(null, "/home/u/.agents/skills/alpha", shared = true),
            source("cursor", "/home/u/.cursor/skills/alpha"),
            source("grok", "/home/u/.grok/skills/alpha"),
        )
        val candidate = RedundantCopyCandidate(shared, listOf("cursor", "grok"), linkedAgentIds = setOf("cursor"))
        val plan = SkillBulkPlanModel.cleanup(listOf(RedundantCopyWork(candidate, SkillBrowserContext(SkillScope.GLOBAL))), null)

        val byChange = plan.directories.associateBy { it.change }
        assertEquals(Path.of("/home/u/.cursor/skills").toString(), byChange.getValue(BulkPlanChange.LINK_REMOVED).path)
        assertEquals(Path.of("/home/u/.grok/skills").toString(), byChange.getValue(BulkPlanChange.COPY_REMOVED).path)
        assertEquals("Clean Up 1 Skill", plan.applyLabel)
    }
}
