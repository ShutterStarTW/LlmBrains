package com.shutterstar.agenthub.environment.skills.ui

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.PreparedSkillSync
import com.shutterstar.agenthub.environment.skills.sync.SkillSyncPlanResult
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAction
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncPlan
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStep
import com.shutterstar.agenthub.environment.skills.sync.model.SyncWarning
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillSyncPlanningRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SkillSyncPreviewModelTest {
    @Test
    fun `should expose reviewed changes and warnings without executing them`() {
        val canonical = Path.of("shared/review")
        val target = Path.of("claude/review")
        val request = SkillSyncPlanningRequest("op", "review", canonical, "fp", emptyList())
        val plan = SkillSyncPlan(
            "op",
            "review",
            canonical,
            listOf(
                SkillSyncStep.BackupExisting("claude", target),
                SkillSyncStep.CopySkill("claude", canonical, target),
            ),
            listOf(SyncWarning("claude", "Copy fallback will be used.")),
        )
        val prepared = PreparedSkillSync(
            SkillSyncPlanResult(plan, request, SyncAction.SHARE, SkillScope.GLOBAL),
            SkillScope.GLOBAL,
            null,
            Path.of("backups"),
        )

        val model = SkillSyncPreviewModel.from(prepared) { "Claude Code" }

        assertTrue(model.canApply)
        assertEquals("Share review", model.title)
        assertEquals("Global · Host", model.context)
        assertEquals("Claude Code", model.targets.single().agentName)
        assertEquals("Not available", model.targets.single().currentState)
        assertTrue(model.targets.single().plannedChange.contains("copy shared skill"))
        assertEquals(1, model.changeCount)
        assertEquals(Path.of("backups/op").toString(), model.backupPath)
        assertEquals(listOf("Copy fallback will be used."), model.warnings)
    }

    @Test
    fun `should disable apply for a warning-only plan`() {
        val canonical = Path.of("shared/review")
        val request = SkillSyncPlanningRequest("op", "review", canonical, "fp", emptyList())
        val prepared = PreparedSkillSync(
            SkillSyncPlanResult(
                SkillSyncPlan("op", "review", canonical, emptyList(), listOf(SyncWarning(null, "Blocked"))),
                request,
                SyncAction.SHARE,
                SkillScope.GLOBAL,
            ),
            SkillScope.GLOBAL,
            null,
            Path.of("backups"),
        )

        assertFalse(SkillSyncPreviewModel.from(prepared) { it }.canApply)
    }
}
