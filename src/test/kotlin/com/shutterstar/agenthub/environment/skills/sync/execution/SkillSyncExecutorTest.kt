package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.writeSkillMd
import com.shutterstar.agenthub.environment.skills.discovery.SkillFingerprint
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.link.CopyStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.FileLinkStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.LinkResult
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStep
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import com.shutterstar.agenthub.environment.skills.sync.ownership.InMemorySyncOwnershipStore
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillSyncPlanner
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillSyncPlanningRequest
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillTargetObserver
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * [FakeTarget] models a [SkillSyncTarget] the way real adapters do: its path is the agent's
 * skills *root*, and the observer appends the canonical skill's directory name itself (always
 * `"canonical"` here, since canonical is always created at `root.resolve("canonical")`).
 */
class SkillSyncExecutorTest {
    @TempDir
    lateinit var root: Path

    private val observer = SkillTargetObserver()
    private val planner = SkillSyncPlanner()
    private val executor = SkillSyncExecutor()
    private val fingerprintCalculator = SkillFingerprint()

    @Test
    fun `not available target gets a link created and verified`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val canonicalFingerprint = fingerprintCalculator.calculate(canonical)!!
        val targetRoot = root.resolve("claude-root")
        val targetPath = targetRoot.resolve("canonical")
        val target = FakeTarget("claude", targetRoot)

        val observed = observer.observe(target, canonical, canonicalFingerprint, SkillScope.GLOBAL, null)
        val request = SkillSyncPlanningRequest("op-1", "skill-1", canonical, canonicalFingerprint, listOf(observed))
        val plan = planner.plan(request)

        val result = executor.execute(
            plan,
            request,
            mapOf("claude" to target),
            SkillScope.GLOBAL,
            null,
            root.resolve("backups"),
        )

        assertEquals(SyncOperationStatus.SUCCESS, result.status)
        assertTrue(result.rollbackAvailable)
        assertTrue(Files.exists(targetPath))
        assertEquals("content", Files.readString(targetPath.resolve("SKILL.md")))
    }

    @Test
    fun `identical unmanaged target is backed up before being replaced with a link, and the backup is restorable`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val canonicalFingerprint = fingerprintCalculator.calculate(canonical)!!
        val targetRoot = root.resolve("claude-root")
        val targetPath = writeSkillMd(targetRoot.resolve("canonical"), "content")
        val target = FakeTarget("claude", targetRoot)

        val observed = observer.observe(target, canonical, canonicalFingerprint, SkillScope.GLOBAL, null)
        val request = SkillSyncPlanningRequest("op-1", "skill-1", canonical, canonicalFingerprint, listOf(observed))
        val plan = planner.plan(request)
        val backupRoot = root.resolve("backups")

        val result = executor.execute(plan, request, mapOf("claude" to target), SkillScope.GLOBAL, null, backupRoot)

        assertEquals(SyncOperationStatus.SUCCESS, result.status)
        assertTrue(result.rollbackAvailable)
        assertEquals("content", Files.readString(targetPath.resolve("SKILL.md")))
        Files.list(backupRoot.resolve("op-1").resolve("claude")).use { assertTrue(it.findFirst().isPresent) }
    }

    @Test
    fun `a mid-group verification failure restores the original content from backup`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val realCanonicalFingerprint = fingerprintCalculator.calculate(canonical)!!
        val targetRoot = root.resolve("claude-root")
        val targetPath = writeSkillMd(targetRoot.resolve("canonical"), "content")
        val target = FakeTarget("claude", targetRoot)

        // Observe with the real fingerprint so status detection (IDENTICAL_UNMANAGED) is correct,
        // then feed the planner/executor a wrong one so VerifyFingerprint fails deterministically
        // after the backup + replace has already happened, without relying on OS permission games.
        val observed = observer.observe(target, canonical, realCanonicalFingerprint, SkillScope.GLOBAL, null)
        val request = SkillSyncPlanningRequest("op-1", "skill-1", canonical, "deliberately-wrong-fingerprint", listOf(observed))
        val plan = planner.plan(request)
        val backupRoot = root.resolve("backups")

        val result = executor.execute(plan, request, mapOf("claude" to target), SkillScope.GLOBAL, null, backupRoot)

        assertEquals(SyncOperationStatus.FAILED, result.status)
        assertTrue(result.errors.isNotEmpty())
        assertEquals("content", Files.readString(targetPath.resolve("SKILL.md")))
        // Confirms restore actually ran (target is a real directory again), not just that content
        // happens to match — canonical and target share identical bytes by definition here, so a
        // leftover junction pointing at canonical would read back the same content too.
        assertFalse(Files.isSymbolicLink(targetPath))
    }

    @Test
    fun `revalidation aborts a target whose state changed since the plan was built, untouched`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val canonicalFingerprint = fingerprintCalculator.calculate(canonical)!!
        val targetRoot = root.resolve("claude-root")
        val targetPath = targetRoot.resolve("canonical")
        val target = FakeTarget("claude", targetRoot)

        val observed = observer.observe(target, canonical, canonicalFingerprint, SkillScope.GLOBAL, null)
        val request = SkillSyncPlanningRequest("op-1", "skill-1", canonical, canonicalFingerprint, listOf(observed))
        val plan = planner.plan(request)

        writeSkillMd(targetPath, "unexpected content placed after the preview")

        val result = executor.execute(
            plan,
            request,
            mapOf("claude" to target),
            SkillScope.GLOBAL,
            null,
            root.resolve("backups"),
        )

        assertEquals(SyncOperationStatus.FAILED, result.status)
        assertTrue(result.errors.single().message.contains("changed since preview"))
        assertEquals("unexpected content placed after the preview", Files.readString(targetPath.resolve("SKILL.md")))
    }

    @Test
    fun `execution aborts before mutation when canonical content changed after preview`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "preview content")
        val canonicalFingerprint = fingerprintCalculator.calculate(canonical)!!
        val targetRoot = root.resolve("claude-root")
        val targetPath = targetRoot.resolve("canonical")
        val target = FakeTarget("claude", targetRoot)
        val observed = observer.observe(target, canonical, canonicalFingerprint, SkillScope.GLOBAL, null)
        val request = SkillSyncPlanningRequest("op-1", "skill-1", canonical, canonicalFingerprint, listOf(observed))
        val plan = planner.plan(request)

        Files.writeString(canonical.resolve("SKILL.md"), "changed after preview")
        val result = executor.execute(
            plan,
            request,
            mapOf("claude" to target),
            SkillScope.GLOBAL,
            null,
            root.resolve("backups"),
        )

        assertEquals(SyncOperationStatus.FAILED, result.status)
        assertTrue(result.errors.single().message.contains("Shared source changed since preview"))
        assertFalse(Files.exists(targetPath))
    }

    @Test
    fun `one failing target does not affect an independent healthy target`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val canonicalFingerprint = fingerprintCalculator.calculate(canonical)!!

        val healthyRoot = root.resolve("cline-root")
        val healthyTargetPath = healthyRoot.resolve("canonical")
        val healthyTarget = FakeTarget("cline", healthyRoot)

        // A plain file where the target's parent directory needs to be created forces a
        // deterministic, portable CreateDirectory failure for this agent only.
        val brokenRoot = Files.createFile(root.resolve("broken-root"))
        val brokenTarget = FakeTarget("claude", brokenRoot)

        val healthyObserved = observer.observe(healthyTarget, canonical, canonicalFingerprint, SkillScope.GLOBAL, null)
        val brokenObserved = observer.observe(brokenTarget, canonical, canonicalFingerprint, SkillScope.GLOBAL, null)
        val request = SkillSyncPlanningRequest(
            "op-1",
            "skill-1",
            canonical,
            canonicalFingerprint,
            listOf(healthyObserved, brokenObserved),
        )
        val plan = planner.plan(request)

        val result = executor.execute(
            plan,
            request,
            mapOf("cline" to healthyTarget, "claude" to brokenTarget),
            SkillScope.GLOBAL,
            null,
            root.resolve("backups"),
        )

        assertEquals(SyncOperationStatus.PARTIAL_SUCCESS, result.status)
        assertEquals(1, result.errors.size)
        assertEquals("content", Files.readString(healthyTargetPath.resolve("SKILL.md")))
    }

    @Test
    fun `revalidation does not spuriously flag drift for a managed COPIED target`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val canonicalFingerprint = fingerprintCalculator.calculate(canonical)!!
        val targetRoot = root.resolve("claude-root")
        val targetPath = writeSkillMd(targetRoot.resolve("canonical"), "content")
        val target = FakeTarget("claude", targetRoot)

        val ownershipStore = InMemorySyncOwnershipStore()
        ownershipStore.record(
            "skill-1",
            ManagedTarget("claude", targetPath.toString(), SkillSyncMode.COPY, EffectiveSyncMode.COPY, canonicalFingerprint),
        )
        val scopedExecutor = SkillSyncExecutor(ownershipStore = ownershipStore)

        val observed = observer.observe(
            target,
            canonical,
            canonicalFingerprint,
            SkillScope.GLOBAL,
            null,
            managedTarget = ownershipStore.managedTarget("skill-1", "claude"),
        )
        assertEquals(SkillTargetStatus.COPIED, observed.status)
        val request = SkillSyncPlanningRequest("op-1", "skill-1", canonical, canonicalFingerprint, listOf(observed))
        val plan = planner.plan(request)

        val result = scopedExecutor.execute(
            plan,
            request,
            mapOf("claude" to target),
            SkillScope.GLOBAL,
            null,
            root.resolve("backups"),
        )

        assertEquals(SyncOperationStatus.SUCCESS, result.status, "errors=${result.errors}; rollback=${result.rollbackErrors}")
        assertTrue(result.errors.none { it.message.contains("changed since preview") })
    }

    @Test
    fun `identical unmanaged copy replacement keeps the live path until the new tree is staged`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val canonicalFingerprint = fingerprintCalculator.calculate(canonical)!!
        val targetRoot = root.resolve("claude-root")
        val targetPath = writeSkillMd(targetRoot.resolve("canonical"), "content")
        val target = CopyOnlyTarget("claude", targetRoot)
        var originalPresentDuringInstall = false
        val ownershipStore = InMemorySyncOwnershipStore()
        val trackingCopy = object : FileLinkStrategy {
            override fun canLink(source: Path, target: Path): Boolean = true
            override fun createLink(source: Path, target: Path): LinkResult {
                // AtomicPathReplace installs into a sibling staging dir; the live skill path must
                // still exist at that moment (delete-then-create would already have removed it).
                if (target.parent == targetPath.parent && target != targetPath) {
                    originalPresentDuringInstall = Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)
                }
                return CopyStrategy().createLink(source, target)
            }
        }
        val localExecutor = SkillSyncExecutor(
            ownershipStore = ownershipStore,
            stepExecutor = SkillSyncStepExecutor(ownershipStore = ownershipStore, copyStrategy = trackingCopy),
        )

        val observed = observer.observe(target, canonical, canonicalFingerprint, SkillScope.GLOBAL, null)
        assertEquals(SkillTargetStatus.IDENTICAL_UNMANAGED, observed.status)
        val request = SkillSyncPlanningRequest("op-1", "skill-1", canonical, canonicalFingerprint, listOf(observed))
        val plan = planner.plan(request)
        assertTrue(plan.steps.any { it is SkillSyncStep.RemoveExisting })
        assertTrue(plan.steps.any { it is SkillSyncStep.CopySkill })

        val result = localExecutor.execute(
            plan,
            request,
            mapOf("claude" to target),
            SkillScope.GLOBAL,
            null,
            root.resolve("backups"),
        )

        assertEquals(SyncOperationStatus.SUCCESS, result.status)
        assertTrue(originalPresentDuringInstall, "RemoveExisting+CopySkill must not delete before staging completes")
        assertEquals("content", Files.readString(targetPath.resolve("SKILL.md")))
    }

    private class FakeTarget(
        override val agentId: String,
        private val global: Path,
    ) : SkillSyncTarget {
        override fun globalSkillDirectory(): Path = global

        override fun projectSkillDirectory(project: DiscoveredProject): Path? = null

        override fun supportsLinkedSkills(): Boolean = true
    }

    private class CopyOnlyTarget(
        override val agentId: String,
        private val global: Path,
    ) : SkillSyncTarget {
        override fun globalSkillDirectory(): Path = global

        override fun projectSkillDirectory(project: DiscoveredProject): Path? = null

        override fun supportsLinkedSkills(): Boolean = false
    }
}
