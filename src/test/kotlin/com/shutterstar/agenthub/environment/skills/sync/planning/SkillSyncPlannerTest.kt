package com.shutterstar.agenthub.environment.skills.sync.planning

import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStep
import com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SkillSyncPlannerTest {
    private val planner = SkillSyncPlanner()
    private val canonicalPath: Path = Path.of("/canonical/php-review")

    @Test
    fun `reproduces the milestone spec acceptance example`() {
        val request = requestWith(
            claudeTarget().copy(status = SkillTargetStatus.NOT_AVAILABLE, availableLinkMode = EffectiveSyncMode.SYMLINK),
            codexTarget().copy(status = SkillTargetStatus.IDENTICAL_UNMANAGED, availableLinkMode = EffectiveSyncMode.SYMLINK),
            cursorTarget().copy(status = SkillTargetStatus.DIFFERENT, availableLinkMode = EffectiveSyncMode.SYMLINK),
        )

        val plan = planner.plan(request)

        val claudeSteps = plan.steps.filter { it.agentId == "claude" }
        assertEquals(
            listOf(
                SkillSyncStep.CreateDirectory::class,
                SkillSyncStep.CreateLink::class,
                SkillSyncStep.VerifyFingerprint::class,
                SkillSyncStep.WriteMetadata::class,
            ),
            claudeSteps.map { it::class },
        )

        val codexSteps = plan.steps.filter { it.agentId == "codex" }
        assertEquals(
            listOf(
                SkillSyncStep.CreateDirectory::class,
                SkillSyncStep.BackupExisting::class,
                SkillSyncStep.RemoveExisting::class,
                SkillSyncStep.CreateLink::class,
                SkillSyncStep.VerifyFingerprint::class,
                SkillSyncStep.WriteMetadata::class,
            ),
            codexSteps.map { it::class },
        )

        assertTrue(plan.steps.none { it.agentId == "cursor" })
        assertEquals(1, plan.warnings.count { it.agentId == "cursor" })
    }

    @Test
    fun `target missing produces create-link steps with no backup`() {
        val plan = planner.plan(
            requestWith(claudeTarget().copy(status = SkillTargetStatus.NOT_AVAILABLE, availableLinkMode = EffectiveSyncMode.SYMLINK)),
        )

        assertTrue(plan.steps.none { it is SkillSyncStep.BackupExisting })
        assertTrue(plan.steps.any { it is SkillSyncStep.CreateLink })
    }

    @Test
    fun `identical unmanaged target backs up before replacing with a link`() {
        val plan = planner.plan(
            requestWith(claudeTarget().copy(status = SkillTargetStatus.IDENTICAL_UNMANAGED, availableLinkMode = EffectiveSyncMode.SYMLINK)),
        )

        assertEquals(
            listOf(
                SkillSyncStep.CreateDirectory::class,
                SkillSyncStep.BackupExisting::class,
                SkillSyncStep.RemoveExisting::class,
                SkillSyncStep.CreateLink::class,
                SkillSyncStep.VerifyFingerprint::class,
                SkillSyncStep.WriteMetadata::class,
            ),
            plan.steps.map { it::class },
        )
    }

    @Test
    fun `managed copy target gets the same backup-and-replace treatment as identical unmanaged`() {
        val plan = planner.plan(
            requestWith(claudeTarget().copy(status = SkillTargetStatus.COPIED, availableLinkMode = EffectiveSyncMode.SYMLINK)),
        )

        assertTrue(plan.steps.any { it is SkillSyncStep.BackupExisting })
        assertTrue(plan.steps.any { it is SkillSyncStep.RemoveExisting })
    }

    @Test
    fun `different target produces a warning and zero steps, never a mutation`() {
        val plan = planner.plan(
            requestWith(claudeTarget().copy(status = SkillTargetStatus.DIFFERENT)),
        )

        assertTrue(plan.steps.isEmpty())
        assertEquals(1, plan.warnings.size)
    }

    @Test
    fun `managed broken link is backed up and replaced`() {
        val plan = planner.plan(
            requestWith(
                claudeTarget().copy(
                    status = SkillTargetStatus.BROKEN_LINK,
                    availableLinkMode = EffectiveSyncMode.SYMLINK,
                    ownershipVerified = true,
                    managedMode = EffectiveSyncMode.SYMLINK,
                    managedLinkTarget = canonicalPath,
                ),
            ),
        )

        assertEquals(
            listOf(
                SkillSyncStep.CreateDirectory::class,
                SkillSyncStep.BackupExisting::class,
                SkillSyncStep.RemoveExisting::class,
                SkillSyncStep.CreateLink::class,
                SkillSyncStep.VerifyFingerprint::class,
                SkillSyncStep.WriteMetadata::class,
            ),
            plan.steps.map { it::class },
        )
    }

    @Test
    fun `missing canonical source produces empty steps regardless of targets`() {
        val request = SkillSyncPlanningRequest(
            operationId = "op-1",
            skillId = "skill-1",
            canonicalPath = canonicalPath,
            canonicalFingerprint = null,
            targets = listOf(claudeTarget().copy(status = SkillTargetStatus.NOT_AVAILABLE)),
        )

        val plan = planner.plan(request)

        assertTrue(plan.steps.isEmpty())
        assertEquals(1, plan.warnings.size)
        assertEquals(null, plan.warnings.single().agentId)
    }

    @Test
    fun `unsupported agent produces a warning and no crash`() {
        val plan = planner.plan(
            requestWith(claudeTarget().copy(status = SkillTargetStatus.UNSUPPORTED, targetPath = null)),
        )

        assertTrue(plan.steps.isEmpty())
        assertEquals(1, plan.warnings.size)
    }

    @Test
    fun `link unavailable falls back to copy`() {
        val plan = planner.plan(
            requestWith(claudeTarget().copy(status = SkillTargetStatus.NOT_AVAILABLE, availableLinkMode = null)),
        )

        assertTrue(plan.steps.any { it is SkillSyncStep.CopySkill })
        assertTrue(plan.steps.none { it is SkillSyncStep.CreateLink })
    }

    @Test
    fun `explicit copy request is honored even when linking is available`() {
        val plan = planner.plan(
            requestWith(
                claudeTarget().copy(
                    status = SkillTargetStatus.NOT_AVAILABLE,
                    requestedMode = SkillSyncMode.COPY,
                    availableLinkMode = EffectiveSyncMode.SYMLINK,
                ),
            ),
        )

        assertTrue(plan.steps.any { it is SkillSyncStep.CopySkill })
        assertTrue(plan.steps.none { it is SkillSyncStep.CreateLink })
    }

    @Test
    fun `already linked target produces no steps`() {
        val plan = planner.plan(
            requestWith(claudeTarget().copy(status = SkillTargetStatus.LINKED)),
        )

        assertTrue(plan.steps.isEmpty())
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun `a NATIVE target (agent reads the shared source directly) produces no steps, like LINKED`() {
        val plan = planner.plan(
            requestWith(claudeTarget().copy(status = SkillTargetStatus.NATIVE)),
        )

        assertTrue(plan.steps.isEmpty())
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun `independent targets do not cross-contaminate steps or warnings`() {
        val plan = planner.plan(
            requestWith(
                claudeTarget().copy(status = SkillTargetStatus.DIFFERENT),
                codexTarget().copy(status = SkillTargetStatus.NOT_AVAILABLE, availableLinkMode = EffectiveSyncMode.SYMLINK),
            ),
        )

        assertTrue(plan.steps.all { it.agentId == "codex" })
        assertTrue(plan.warnings.all { it.agentId == "claude" })
    }

    private fun requestWith(vararg targets: ObservedSkillTarget) = SkillSyncPlanningRequest(
        operationId = "op-1",
        skillId = "skill-1",
        canonicalPath = canonicalPath,
        canonicalFingerprint = "canonical-fingerprint",
        targets = targets.toList(),
    )

    private fun claudeTarget() = ObservedSkillTarget(
        agentId = "claude",
        targetPath = Path.of("/home/user/.claude/skills/php-review"),
        status = SkillTargetStatus.NOT_AVAILABLE,
    )

    private fun codexTarget() = ObservedSkillTarget(
        agentId = "codex",
        targetPath = Path.of("/home/user/.codex/skills/php-review"),
        status = SkillTargetStatus.NOT_AVAILABLE,
        fingerprint = "canonical-fingerprint",
    )

    private fun cursorTarget() = ObservedSkillTarget(
        agentId = "cursor",
        targetPath = Path.of("/home/user/.cursor/skills/php-review"),
        status = SkillTargetStatus.NOT_AVAILABLE,
        fingerprint = "some-other-fingerprint",
    )
}
