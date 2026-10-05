package com.shutterstar.agenthub.environment.skills.sync

import com.shutterstar.agenthub.writeSkillMd
import com.shutterstar.agenthub.environment.skills.discovery.SharedSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SkillFingerprint
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.environment.skills.sync.execution.BackupService
import com.shutterstar.agenthub.environment.skills.sync.execution.SkillSyncExecutor
import com.shutterstar.agenthub.environment.skills.sync.link.FileLinkStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.LinkResult
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncRequest
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncResult
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStep
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStepResult
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SyncTargetOutcome
import com.shutterstar.agenthub.environment.skills.sync.ownership.InMemorySyncOwnershipStore
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillSyncPlanner
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillSyncPlanningRequest
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SkillSyncSafetyRegressionTest {
    @TempDir
    lateinit var root: Path

    private class Target(
        override val agentId: String,
        private val directory: Path,
        private val links: Boolean = false,
    ) : SkillSyncTarget {
        override fun globalSkillDirectory(): Path = directory

        override fun projectSkillDirectory(project: DiscoveredProject): Path = directory

        override fun supportsLinkedSkills(): Boolean = links
    }

    @Test
    fun `ownership keeps identical skill ids in separate physical projects`() {
        val store = InMemorySyncOwnershipStore()
        val projectA = root.resolve("project-a")
        val projectB = root.resolve("project-b")
        val keyA = SkillInstanceKey.host("review", SkillScope.PROJECT, projectA.resolve(".agents/skills/review"), projectA)
        val keyB = SkillInstanceKey.host("review", SkillScope.PROJECT, projectB.resolve(".agents/skills/review"), projectB)
        val first = ManagedTarget("claude", projectA.resolve(".claude/skills/review").toString(), SkillSyncMode.COPY, EffectiveSyncMode.COPY, "fp-a")
        val second = ManagedTarget("claude", projectB.resolve(".claude/skills/review").toString(), SkillSyncMode.COPY, EffectiveSyncMode.COPY, "fp-b")

        store.record(keyA, first)
        store.record(keyB, second)

        assertEquals(first, store.managedTarget(keyA, "claude"))
        assertEquals(second, store.managedTarget(keyB, "claude"))
        assertEquals(null, store.managedTarget("review", "claude"), "Ambiguous legacy lookup must fail closed")
    }

    @Test
    fun `ordinary share does not take over an unmanaged broken link`() {
        val observed = ObservedSkillTarget(
            "claude",
            root.resolve("target"),
            SkillTargetStatus.BROKEN_LINK,
            ownershipVerified = false,
        )
        val request = SkillSyncPlanningRequest("op", "review", root.resolve("canonical"), "fp", listOf(observed))

        val plan = SkillSyncPlanner().plan(request)

        assertTrue(plan.steps.none { it is SkillSyncStep.RemoveExisting })
        assertTrue(plan.warnings.any { it.message.contains("takeover", ignoreCase = true) })
    }

    @Test
    fun `copy fallback preserves requested symlink mode`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val store = InMemorySyncOwnershipStore()
        val service = SkillSyncEngine(ownershipStore = store)
        val targets = mapOf("claude" to Target("claude", root.resolve("claude")))
        val result = service.runner.execute(
            plan(service, SkillSyncRequest.ShareSkill("review", "claude", SkillSyncMode.SYMLINK), skill(canonical), targets, "fallback"),
            targets,
            SkillScope.GLOBAL,
            null,
            root.resolve("backups"),
        )

        assertEquals(SyncOperationStatus.SUCCESS, result.status)
        assertEquals(SkillSyncMode.SYMLINK, store.managedTarget(requireNotNull(result.instanceKey), "claude")?.requestedMode)
        assertEquals(EffectiveSyncMode.COPY, store.managedTarget(requireNotNull(result.instanceKey), "claude")?.effectiveMode)
    }

    @Test
    fun `share everywhere reports a blocked conflict as partial success`() {
        // "cline" (not a supportsSharedAgentSkills agent) so it gets a real per-agent
        // copy/conflict here instead of short-circuiting to NATIVE.
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        writeSkillMd(root.resolve("cline/canonical"), "different")
        val service = SkillSyncEngine()
        val targets = mapOf(
            "claude" to Target("claude", root.resolve("claude")),
            "cline" to Target("cline", root.resolve("cline")),
        )

        val result = service.runner.execute(
            plan(service, SkillSyncRequest.ShareSkillEverywhere("review", SkillSyncMode.COPY), skill(canonical), targets, "partial"),
            targets,
            SkillScope.GLOBAL,
            null,
            root.resolve("backups"),
        )

        assertEquals(SyncOperationStatus.PARTIAL_SUCCESS, result.status)
        assertEquals(SyncTargetOutcome.CHANGED, result.targetResults.single { it.agentId == "claude" }.outcome)
        assertEquals(SyncTargetOutcome.BLOCKED, result.targetResults.single { it.agentId == "cline" }.outcome)
    }

    @Test
    fun `undo promotion retains a canonical used by a newer share`() {
        val source = writeSkillMd(root.resolve("claude/review"), "content")
        val store = InMemorySyncOwnershipStore()
        val service = SkillSyncEngine(ownershipStore = store, sharedSkillDirectory = SharedSkillProvider(root))
        val targets = mapOf(
            "claude" to Target("claude", root.resolve("claude"), links = true),
            "cline" to Target("cline", root.resolve("cline"), links = true),
        )
        val promotion = plan(
            service,
            SkillSyncRequest.PromoteSkill("review", "claude", source, SkillScope.GLOBAL),
            skill(source, shared = false),
            targets,
            "promote",
        )
        val promoted = service.runner.execute(promotion, targets, SkillScope.GLOBAL, null, root.resolve("backups"))
        val canonical = promotion.plan.canonicalPath
        val shared = service.runner.execute(
            plan(service, SkillSyncRequest.ShareSkill("review", "cline"), skill(canonical), targets, "share-later"),
            targets,
            SkillScope.GLOBAL,
            null,
            root.resolve("backups"),
        )

        assertEquals(SyncOperationStatus.SUCCESS, promoted.status)
        assertEquals(SyncOperationStatus.SUCCESS, shared.status)
        val undo = service.runner.undo(promoted)
        assertTrue(undo.errors.any { it.message.contains("newer managed target") })
        assertTrue(Files.exists(canonical))
        assertTrue(Files.isSameFile(canonical, root.resolve("cline/review")))
    }

    @Test
    fun `undo validates every path before changing any of them`() {
        val first = writeSkillMd(root.resolve("first"), "synced")
        val second = writeSkillMd(root.resolve("second"), "synced")
        val verification = SkillFingerprint()
        val firstFingerprint = verification.calculate(first)!!
        val secondFingerprint = verification.calculate(second)!!
        val result = SkillSyncResult(
            operationId = "multi",
            status = SyncOperationStatus.SUCCESS,
            appliedSteps = listOf(
                SkillSyncStepResult(SkillSyncStep.CopySkill("claude", root.resolve("source-a"), first), true),
                SkillSyncStepResult(SkillSyncStep.VerifyFingerprint("claude", first, firstFingerprint), true),
                SkillSyncStepResult(SkillSyncStep.CopySkill("codex", root.resolve("source-b"), second), true),
                SkillSyncStepResult(SkillSyncStep.VerifyFingerprint("codex", second, secondFingerprint), true),
            ),
            errors = emptyList(),
            rollbackAvailable = true,
            skillId = "review",
            previousManagedTargets = mapOf("claude" to null, "codex" to null),
        )
        Files.writeString(first.resolve("SKILL.md"), "changed later")

        val undo = SkillSyncEngine().runner.undo(result)

        assertTrue(undo.errors.isNotEmpty())
        assertTrue(Files.exists(second), "Preflight must not remove an earlier path before detecting later drift")
        assertEquals("synced", Files.readString(second.resolve("SKILL.md")))
    }

    @Test
    fun `stop sharing undo restores the managed link representation`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val service = SkillSyncEngine()
        val targetPath = root.resolve("claude/canonical")
        val targets = mapOf("claude" to Target("claude", root.resolve("claude"), links = true))
        val shared = service.runner.execute(
            plan(service, SkillSyncRequest.ShareSkill("review", "claude"), skill(canonical), targets, "share"),
            targets,
            SkillScope.GLOBAL,
            null,
            root.resolve("backups"),
        )
        val stopped = service.runner.execute(
            plan(service, SkillSyncRequest.StopSharing("review", "claude"), skill(canonical), targets, "stop"),
            targets,
            SkillScope.GLOBAL,
            null,
            root.resolve("backups"),
        )

        assertEquals(SyncOperationStatus.SUCCESS, shared.status)
        assertEquals(SyncOperationStatus.SUCCESS, stopped.status, stopped.errors.toString())
        assertFalse(Files.exists(targetPath))
        val undone = service.runner.undo(stopped)
        assertTrue(undone.errors.isEmpty(), undone.errors.toString())
        assertTrue(Files.isSameFile(canonical, targetPath), "Undo must restore the link or junction, not a detached copy")
    }

    @Test
    fun `failed rollback exposes its backup and restoration error`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        writeSkillMd(root.resolve("claude/canonical"), "content")
        val targets = mapOf("claude" to Target("claude", root.resolve("claude")))
        val service = SkillSyncEngine()
        val preview = plan(service, SkillSyncRequest.ShareSkill("review", "claude", SkillSyncMode.COPY), skill(canonical), targets, "rollback")
        val invalidPlan = preview.plan.copy(steps = preview.plan.steps.map { step ->
            if (step is SkillSyncStep.VerifyFingerprint) step.copy(expectedFingerprint = "verification-fails") else step
        })
        val failingRestore = BackupService(
            copyStrategy = object : FileLinkStrategy {
                override fun canLink(source: Path, target: Path): Boolean = true

                override fun createLink(source: Path, target: Path): LinkResult = LinkResult.Failure("disk full during restore")
            },
        )
        val executor = SkillSyncExecutor(backupService = failingRestore)

        val result = executor.execute(
            invalidPlan,
            preview.planningRequest,
            targets,
            SkillScope.GLOBAL,
            null,
            root.resolve("backups"),
        )

        assertEquals(SyncOperationStatus.FAILED, result.status)
        assertTrue(result.restorableBackups.isNotEmpty())
        assertTrue(result.errors.any { it.message.contains("restore", ignoreCase = true) })
        assertTrue(result.rollbackErrors.any { it.message.contains("restore", ignoreCase = true) })
        assertTrue(result.recoveryRequired)
        assertNotNull(result.targetResults.singleOrNull { it.agentId == "claude" })
    }

    private fun plan(
        service: SkillSyncEngine,
        request: SkillSyncRequest,
        skill: AgentSkill,
        targets: Map<String, SkillSyncTarget>,
        operationId: String,
    ) = service.planner.plan(request, skill, operationId, targets, targets.keys, SkillScope.GLOBAL, null)

    private fun skill(path: Path, shared: Boolean = true) = AgentSkill(
        SkillIdentity("review"),
        "review",
        null,
        SkillScope.GLOBAL,
        listOf(SkillSource(if (shared) null else "claude", path.toString(), SkillScope.GLOBAL, shared, "fixture")),
        emptySet(),
        SkillConsistency.SINGLE_SOURCE,
    )
}
