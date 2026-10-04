package com.shutterstar.agenthub.environment.skills.sync

import com.shutterstar.agenthub.writeSkillMd
import com.shutterstar.agenthub.environment.skills.discovery.SharedSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SkillFingerprint
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.environment.skills.sync.audit.InMemorySyncAuditTrail
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAction
import com.shutterstar.agenthub.environment.skills.sync.execution.BackupMetadataStore
import com.shutterstar.agenthub.environment.skills.sync.execution.BackupService
import com.shutterstar.agenthub.environment.skills.sync.execution.DirectoryDeleter
import com.shutterstar.agenthub.environment.skills.sync.execution.StoredBackupRecord
import com.shutterstar.agenthub.environment.skills.sync.link.CopyStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.FileLinkStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.LinkResult
import com.shutterstar.agenthub.environment.skills.sync.model.ConflictResolution
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncRequest
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStep
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import com.shutterstar.agenthub.environment.skills.sync.ownership.InMemorySyncOwnershipStore
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import com.shutterstar.agenthub.environment.skills.sync.target.ClaudeSkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.target.AntigravitySkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * [FakeTarget] models a [SkillSyncTarget] the way real adapters do: its path is the agent's
 * skills *root*, and the observer appends the canonical skill's directory name itself (always
 * `"canonical"` here, since canonical is always created at `root.resolve("canonical")`).
 */
class SkillSyncServiceTest {
    @TempDir
    lateinit var root: Path

    private val service = SkillSyncService()

    @Test
    fun `ShareSkill against a not-available target produces create-link steps`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val target = FakeTarget("claude", root.resolve("claude-root"))

        val result = service.plan(
            SkillSyncRequest.ShareSkill(skillId = "skill-1", targetAgentId = "claude"),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.any { it is SkillSyncStep.CreateLink })
        assertTrue(result.plan.warnings.isEmpty())
    }

    @Test
    fun `ShareSkill replaces an existing identical directory with a link when linking is supported`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val targetRoot = root.resolve("claude-root")
        writeSkillMd(targetRoot.resolve("canonical"), "content")
        val target = FakeTarget("claude", targetRoot)

        val result = service.plan(
            SkillSyncRequest.ShareSkill("skill-1", "claude", SkillSyncMode.SYMLINK),
            agentSkill(sharedPath = canonicalDir),
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.any { it is SkillSyncStep.CreateLink })
        assertTrue(result.plan.steps.none { it is SkillSyncStep.CopySkill })
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["SHARE identical copy", "KEEP_CANONICAL", "KEEP_TARGET"])
    fun `backupBeforeReplacement = false skips the BackupExisting step but still removes the old directory`(kind: String) {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "canonical content")
        val targetRoot = root.resolve("claude-root")
        val shareIdentical = kind.startsWith("SHARE")
        writeSkillMd(targetRoot.resolve("canonical"), if (shareIdentical) "canonical content" else "conflicting content")
        val request = when {
            shareIdentical -> SkillSyncRequest.ShareSkill("skill-1", "claude", SkillSyncMode.SYMLINK)
            else -> SkillSyncRequest.ResolveConflict("skill-1", "claude", ConflictResolution.valueOf(kind))
        }

        val result = service.plan(
            request,
            agentSkill(sharedPath = canonicalDir),
            "op-1",
            mapOf("claude" to FakeTarget("claude", targetRoot)),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
            backupBeforeReplacement = false,
        )

        assertTrue(result.plan.steps.none { it is SkillSyncStep.BackupExisting })
        assertTrue(result.plan.steps.any { it is SkillSyncStep.RemoveExisting }, "still removes the old directory, just without backing it up first")
    }

    @Test
    fun `planning refuses a scope that differs from the canonical skill`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val target = FakeTarget("claude", root.resolve("claude-root"))

        val result = service.plan(
            SkillSyncRequest.ShareSkill("skill-1", "claude"),
            agentSkill(sharedPath = canonicalDir),
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.PROJECT,
            null,
        )

        assertTrue(result.plan.steps.isEmpty())
        assertTrue(result.plan.warnings.single().message.contains("scope does not match"))
    }

    @Test
    fun `execution refuses a scope that differs from the reviewed plan`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val targetRoot = root.resolve("claude-root")
        val target = FakeTarget("claude", targetRoot)
        val plan = service.plan(
            SkillSyncRequest.ShareSkill("skill-1", "claude"),
            agentSkill(sharedPath = canonicalDir),
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        val result = service.execute(plan, mapOf("claude" to target), SkillScope.PROJECT, null, root.resolve("backups"))

        assertEquals(SyncOperationStatus.FAILED, result.status)
        assertFalse(Files.exists(targetRoot.resolve("canonical")))
    }

    @Test
    fun `ShareSkill on an unpromoted skill returns a warning without a canonical source`() {
        val skill = agentSkill(agentOnlyPath = root.resolve("claude-only"))
        val target = FakeTarget("claude", root.resolve("claude-root"))

        val result = service.plan(
            SkillSyncRequest.ShareSkill(skillId = "skill-1", targetAgentId = "claude"),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.isEmpty())
        assertEquals(1, result.plan.warnings.size)
    }

    @Test
    fun `ShareSkillEverywhere only targets installed agents, even when an adapter exists for others`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val claudeTarget = FakeTarget("claude", root.resolve("claude-root"))
        val codexTarget = FakeTarget("codex", root.resolve("codex-root"))

        val result = service.plan(
            SkillSyncRequest.ShareSkillEverywhere(skillId = "skill-1"),
            skill,
            "op-1",
            mapOf("claude" to claudeTarget, "codex" to codexTarget),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.any { it.agentId == "claude" })
        assertTrue(result.plan.steps.none { it.agentId == "codex" })
    }

    @Test
    fun `ShareSkillEverywhere warns about an installed, skill-capable agent that has no adapter yet, instead of silently dropping it`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val claudeTarget = FakeTarget("claude", root.resolve("claude-root"))

        val result = service.plan(
            SkillSyncRequest.ShareSkillEverywhere(skillId = "skill-1"),
            skill,
            "op-1",
            mapOf("claude" to claudeTarget),
            setOf("claude", "antigravity"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.any { it.agentId == "claude" })
        assertEquals(
            listOf("antigravity is not a supported sync target."),
            result.plan.warnings.map { it.message },
        )
    }

    @Test
    fun `ShareSkill against an agent that reads the shared source natively produces no steps`() {
        // "codex" has supportsSharedAgentSkills = true in AgentCapabilityRegistry - it already
        // reads .agents/skills directly, so creating a link/copy in its own skills root would be a
        // redundant duplicate. The FakeTarget root below is never even touched.
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val target = FakeTarget("codex", root.resolve("codex-root"))

        val result = service.plan(
            SkillSyncRequest.ShareSkill(skillId = "skill-1", targetAgentId = "codex"),
            skill,
            "op-1",
            mapOf("codex" to target),
            setOf("codex"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.isEmpty())
        assertTrue(result.plan.warnings.isEmpty())
        assertEquals(SkillTargetStatus.NATIVE, result.planningRequest.targets.single().status)
        assertFalse(Files.exists(root.resolve("codex-root")))
    }

    @Test
    fun `StopSharing an agent that reads the shared source natively is a no-op with an explanatory warning`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val target = FakeTarget("codex", root.resolve("codex-root"))

        val result = service.plan(
            SkillSyncRequest.StopSharing(skillId = "skill-1", targetAgentId = "codex"),
            skill,
            "op-1",
            mapOf("codex" to target),
            setOf("codex"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.isEmpty())
        assertEquals(1, result.plan.warnings.size)
        assertTrue(result.plan.warnings.single().message.contains("nothing to stop sharing"))
    }

    @Test
    fun `StopSharing on a manually created link refuses to remove it`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = Files.createDirectories(root.resolve("claude-root"))
        val targetPath = targetRoot.resolve("canonical")
        val linked = runCatching { Files.createSymbolicLink(targetPath, canonicalDir) }.isSuccess
        assumeTrue(linked, "symlink creation requires elevated privilege on this machine")
        val target = FakeTarget("claude", targetRoot)

        val result = service.plan(
            SkillSyncRequest.StopSharing(skillId = "skill-1", targetAgentId = "claude"),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.isEmpty())
        assertEquals(1, result.plan.warnings.size)
    }

    @Test
    fun `StopSharing on an unmanaged copy produces a warning instead of guessing ownership`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = root.resolve("claude-root")
        writeSkillMd(targetRoot.resolve("canonical"), "content")
        val target = FakeTarget("claude", targetRoot)

        val result = service.plan(
            SkillSyncRequest.StopSharing(skillId = "skill-1", targetAgentId = "claude"),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.isEmpty())
        assertEquals(1, result.plan.warnings.size)
    }

    @Test
    fun `StopSharing on a copied managed target backs it up before removal`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = root.resolve("claude-root")
        val targetPath = writeSkillMd(targetRoot.resolve("canonical"), "content")
        val target = FakeTarget("claude", targetRoot)
        val ownershipStore = InMemorySyncOwnershipStore()
        ownershipStore.record(
            "skill-1",
            ManagedTarget(
                "claude",
                targetPath.toString(),
                SkillSyncMode.COPY,
                EffectiveSyncMode.COPY,
                SkillFingerprint().calculate(targetPath),
            ),
        )
        val service = SkillSyncService(ownershipStore = ownershipStore)

        val result = service.plan(
            SkillSyncRequest.StopSharing(skillId = "skill-1", targetAgentId = "claude"),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertEquals(
            listOf(SkillSyncStep.BackupExisting::class, SkillSyncStep.RemoveExisting::class),
            result.plan.steps.map { it::class },
        )
        assertTrue(result.plan.warnings.isEmpty())
    }

    @Test
    fun `backupBeforeReplacement = false skips StopSharing's BackupExisting step but still removes the target`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = root.resolve("claude-root")
        val targetPath = writeSkillMd(targetRoot.resolve("canonical"), "content")
        val target = FakeTarget("claude", targetRoot)
        val ownershipStore = InMemorySyncOwnershipStore()
        ownershipStore.record(
            "skill-1",
            ManagedTarget(
                "claude",
                targetPath.toString(),
                SkillSyncMode.COPY,
                EffectiveSyncMode.COPY,
                SkillFingerprint().calculate(targetPath),
            ),
        )
        val service = SkillSyncService(ownershipStore = ownershipStore)

        val result = service.plan(
            SkillSyncRequest.StopSharing(skillId = "skill-1", targetAgentId = "claude"),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
            backupBeforeReplacement = false,
        )

        assertEquals(listOf(SkillSyncStep.RemoveExisting::class), result.plan.steps.map { it::class })
    }

    @Test
    fun `after a COPY-mode ShareSkill executes, a second plan observes the target as COPIED`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = root.resolve("claude-root")
        val target = FakeTarget("claude", targetRoot)
        val service = SkillSyncService()

        val firstPlan = service.plan(
            SkillSyncRequest.ShareSkill(skillId = "skill-1", targetAgentId = "claude", mode = SkillSyncMode.COPY),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )
        val firstResult = service.execute(firstPlan, mapOf("claude" to target), SkillScope.GLOBAL, null, root.resolve("backups"))
        assertEquals(SyncOperationStatus.SUCCESS, firstResult.status)

        val secondPlan = service.plan(
            SkillSyncRequest.ShareSkill(skillId = "skill-1", targetAgentId = "claude", mode = SkillSyncMode.COPY),
            skill,
            "op-2",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertEquals(SkillTargetStatus.COPIED, secondPlan.planningRequest.targets.single().status)
    }

    @Test
    fun `ResyncSkill on a drifted managed target backs up and replaces it, unlike a normal conflict`() {
        val targetRoot = root.resolve("claude-root")
        val targetPath = writeSkillMd(targetRoot.resolve("canonical"), "old canonical content")
        val previousFingerprint = SkillFingerprint().calculate(targetPath)
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "canonical content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val target = FakeTarget("claude", targetRoot)
        val ownershipStore = InMemorySyncOwnershipStore()
        ownershipStore.record(
            "skill-1",
            ManagedTarget("claude", targetPath.toString(), SkillSyncMode.COPY, EffectiveSyncMode.COPY, previousFingerprint),
        )
        val service = SkillSyncService(ownershipStore = ownershipStore)

        val result = service.plan(
            SkillSyncRequest.ResyncSkill(skillId = "skill-1", targetAgentId = "claude"),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.warnings.isEmpty())
        assertEquals(
            listOf(
                SkillSyncStep.CreateDirectory::class,
                SkillSyncStep.BackupExisting::class,
                SkillSyncStep.RemoveExisting::class,
                SkillSyncStep.CopySkill::class,
                SkillSyncStep.VerifyFingerprint::class,
                SkillSyncStep.WriteMetadata::class,
            ),
            result.plan.steps.map { it::class },
        )
    }

    @Test
    fun `ResyncSkill executed actually fixes the drift on disk`() {
        val targetRoot = root.resolve("claude-root")
        val targetPath = writeSkillMd(targetRoot.resolve("canonical"), "old canonical content")
        val previousFingerprint = SkillFingerprint().calculate(targetPath)
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "canonical content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val target = FakeTarget("claude", targetRoot)
        val ownershipStore = InMemorySyncOwnershipStore()
        ownershipStore.record(
            "skill-1",
            ManagedTarget("claude", targetPath.toString(), SkillSyncMode.COPY, EffectiveSyncMode.COPY, previousFingerprint),
        )
        val service = SkillSyncService(ownershipStore = ownershipStore)

        val planResult = service.plan(
            SkillSyncRequest.ResyncSkill(skillId = "skill-1", targetAgentId = "claude"),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )
        val syncResult = service.execute(planResult, mapOf("claude" to target), SkillScope.GLOBAL, null, root.resolve("backups"))

        assertEquals(SyncOperationStatus.SUCCESS, syncResult.status)
        assertEquals("canonical content", Files.readString(targetPath.resolve("SKILL.md")))
    }

    @Test
    fun `managedTargetIds reflects ownership records and ignores a mismatched scope`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "canonical content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val ownershipStore = InMemorySyncOwnershipStore()
        ownershipStore.record(
            "skill-1",
            ManagedTarget("claude", root.resolve("claude-root/canonical").toString(), SkillSyncMode.COPY, EffectiveSyncMode.COPY, "fixture"),
        )
        val service = SkillSyncService(ownershipStore = ownershipStore)

        assertEquals(setOf("claude"), service.managedTargetIds(skill, SkillScope.GLOBAL, null))
        assertEquals(emptySet<String>(), service.managedTargetIds(skill, SkillScope.PROJECT, null))
    }

    @Test
    fun `undoOperation reverses a past Share purely from disk, proving it does not need the original in-memory result`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = root.resolve("claude-root")
        val target = FakeTarget("claude", targetRoot)
        val backupRoot = root.resolve("backups")
        val ownershipStore = InMemorySyncOwnershipStore()
        val originalService = SkillSyncService(ownershipStore = ownershipStore)

        val plan = originalService.plan(
            SkillSyncRequest.ShareSkill(skillId = "skill-1", targetAgentId = "claude"),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )
        val shareResult = originalService.execute(plan, mapOf("claude" to target), SkillScope.GLOBAL, null, backupRoot)
        assertEquals(SyncOperationStatus.SUCCESS, shareResult.status)
        val targetPath = targetRoot.resolve("canonical")
        assertTrue(Files.exists(targetPath.resolve("SKILL.md")))
        assertEquals(setOf("claude"), originalService.managedTargetIds(skill, SkillScope.GLOBAL, null))

        // Simulate an IDE restart: a brand-new SkillSyncService instance, holding no reference to
        // `shareResult` at all — only the ownership store (which is really persisted) survives.
        val restartedService = SkillSyncService(ownershipStore = ownershipStore)

        val preview = restartedService.previewUndoOperation("op-1", backupRoot)
        assertNotNull(preview)
        assertEquals("skill-1", preview!!.skillId)
        assertEquals(setOf("claude"), preview.affectedAgents)
        assertEquals(listOf(targetPath.toString()), preview.affectedPaths)

        val undoResult = restartedService.undoOperation("op-1", backupRoot)

        assertNotNull(undoResult)
        assertTrue(undoResult!!.errors.isEmpty(), "Undo errors: ${undoResult.errors}")
        assertFalse(Files.exists(targetPath), "Undo should remove what Share created")
        assertFalse("claude" in restartedService.managedTargetIds(skill, SkillScope.GLOBAL, null), "Undo should restore ownership to unmanaged")
    }

    @Test
    fun `undoOperation returns null when no journal was ever written for that operation id`() {
        assertNull(service.previewUndoOperation("never-happened", root.resolve("backups")))
        assertNull(service.undoOperation("never-happened", root.resolve("backups")))
    }

    @Test
    fun `persisted undo refuses a replacement after its required backup was pruned`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = root.resolve("claude-root")
        val targetPath = writeSkillMd(targetRoot.resolve("canonical"), "content")
        val target = FakeTarget("claude", targetRoot)
        val ownershipStore = InMemorySyncOwnershipStore()
        val localService = SkillSyncService(ownershipStore = ownershipStore)
        val backupRoot = root.resolve("backups")
        val prepared = localService.plan(
            SkillSyncRequest.ShareSkill("skill-1", "claude", SkillSyncMode.COPY),
            skill,
            "replacement",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )
        val result = localService.execute(prepared, mapOf("claude" to target), SkillScope.GLOBAL, null, backupRoot)
        val backup = result.restorableBackups.single()
        DirectoryDeleter.deleteRecursively(backup.backupPath)
        Files.deleteIfExists(BackupMetadataStore.sidecarPathFor(backup.backupPath))

        assertNull(localService.previewUndoOperation("replacement", backupRoot))
        val undo = localService.undoOperation("replacement", backupRoot)

        assertNotNull(undo)
        assertTrue(undo!!.errors.any { it.message.contains("Required backup") })
        assertEquals("content", Files.readString(targetPath.resolve("SKILL.md")))
    }

    @Test
    fun `Restore Backup refuses to replace current content when its safety backup fails`() {
        val backupRoot = root.resolve("backups")
        val target = writeSkillMd(root.resolve("target"), "old content")
        val key = SkillInstanceKey("host", SkillScope.GLOBAL, root.toString(), "skill-1")
        val selected = BackupService().backup(
            "claude",
            "skill-1",
            target,
            backupRoot,
            "old",
            instanceKey = key,
        )!!
        Files.writeString(target.resolve("SKILL.md"), "current content")
        val failingBackupService = BackupService(
            copyStrategy = object : FileLinkStrategy {
                override fun canLink(source: Path, target: Path): Boolean = true
                override fun createLink(source: Path, target: Path): LinkResult = LinkResult.Failure("disk full")
            },
            restoreCopyStrategy = CopyStrategy(),
        )
        val localService = SkillSyncService(backupService = failingBackupService)

        val result = localService.restoreBackup(
            StoredBackupRecord(key, "claude", selected),
            backupRoot,
            "restore",
        )

        assertEquals(SyncOperationStatus.FAILED, result.status)
        assertTrue(result.errors.any { it.message.contains("not attempted") })
        assertEquals("current content", Files.readString(target.resolve("SKILL.md")))
    }

    @Test
    fun `RepairSkill on a broken managed link produces remove-and-recreate steps`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = Files.createDirectories(root.resolve("claude-root"))
        val targetPath = targetRoot.resolve("canonical")
        val linked = runCatching { Files.createSymbolicLink(targetPath, root.resolve("does-not-exist")) }.isSuccess
        assumeTrue(linked, "symlink creation requires elevated privilege on this machine")
        val target = FakeTarget("claude", targetRoot)
        val ownershipStore = InMemorySyncOwnershipStore()
        ownershipStore.record(
            "skill-1",
            ManagedTarget("claude", targetPath.toString(), SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, "content"),
        )
        val service = SkillSyncService(ownershipStore = ownershipStore)

        val result = service.plan(
            SkillSyncRequest.RepairSkill(skillId = "skill-1", targetAgentId = "claude"),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.warnings.isEmpty())
        assertTrue(result.plan.steps.any { it is SkillSyncStep.RemoveExisting })
        assertTrue(result.plan.steps.any { it is SkillSyncStep.CreateLink || it is SkillSyncStep.CopySkill })
    }

    @Test
    fun `ResyncSkill on an unmanaged target returns a warning instead of touching it`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "canonical content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = root.resolve("claude-root")
        writeSkillMd(targetRoot.resolve("canonical"), "drifted content")
        val target = FakeTarget("claude", targetRoot)

        val result = service.plan(
            SkillSyncRequest.ResyncSkill(skillId = "skill-1", targetAgentId = "claude"),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.isEmpty())
        assertEquals(1, result.plan.warnings.size)
    }

    @Test
    fun `ResyncSkill on an already-linked managed target is a no-op`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = Files.createDirectories(root.resolve("claude-root"))
        val targetPath = targetRoot.resolve("canonical")
        val linked = runCatching { Files.createSymbolicLink(targetPath, canonicalDir) }.isSuccess
        assumeTrue(linked, "symlink creation requires elevated privilege on this machine")
        val target = FakeTarget("claude", targetRoot)
        val ownershipStore = InMemorySyncOwnershipStore()
        ownershipStore.record(
            "skill-1",
            ManagedTarget("claude", targetPath.toString(), SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, "content"),
        )
        val service = SkillSyncService(ownershipStore = ownershipStore)

        val result = service.plan(
            SkillSyncRequest.ResyncSkill(skillId = "skill-1", targetAgentId = "claude"),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.isEmpty())
        assertTrue(result.plan.warnings.isEmpty())
    }

    @Test
    fun `RepairSkill with no target agent and nothing managed returns a warning instead of doing anything`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)

        val result = service.plan(
            SkillSyncRequest.RepairSkill(skillId = "skill-1", targetAgentId = null),
            skill,
            "op-1",
            emptyMap(),
            emptySet(),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.isEmpty())
        assertEquals(1, result.plan.warnings.size)
    }

    @Test
    fun `RepairSkill with no target agent repairs every managed agent, isolating one unverifiable agent's warning`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val claudeRoot = root.resolve("claude-root")
        val claudeTargetPath = claudeRoot.resolve("canonical")
        val linked = runCatching { Files.createSymbolicLink(claudeTargetPath, root.resolve("does-not-exist")) }.isSuccess
        assumeTrue(linked, "symlink creation requires elevated privilege on this machine")
        val claudeTarget = FakeTarget("claude", claudeRoot)
        val codexTarget = FakeTarget("codex", root.resolve("codex-root"))

        val ownershipStore = InMemorySyncOwnershipStore()
        ownershipStore.record(
            "skill-1",
            ManagedTarget("claude", claudeTargetPath.toString(), SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, null),
        )
        // codex is recorded as managed but has no adapter in targetsByAgentId below — must warn, not crash.
        ownershipStore.record(
            "skill-1",
            ManagedTarget("missing-adapter", root.resolve("nowhere").toString(), SkillSyncMode.COPY, EffectiveSyncMode.COPY, null),
        )
        val serviceWithOwnership = SkillSyncService(ownershipStore = ownershipStore)

        val result = serviceWithOwnership.plan(
            SkillSyncRequest.RepairSkill(skillId = "skill-1", targetAgentId = null),
            skill,
            "op-1",
            mapOf("claude" to claudeTarget, "codex" to codexTarget),
            setOf("claude", "codex"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.any { it.agentId == "claude" }, "The broken managed link should get repair steps")
        assertTrue(result.plan.warnings.any { it.agentId == "missing-adapter" }, "An agent with no adapter should warn, not fail the whole plan")
    }

    @Test
    fun `ResolveConflict KEEP_CANONICAL replaces the target's content with canonical's`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "canonical content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = root.resolve("claude-root")
        val targetPath = writeSkillMd(targetRoot.resolve("canonical"), "conflicting content")
        val target = FakeTarget("claude", targetRoot)

        val planResult = service.plan(
            SkillSyncRequest.ResolveConflict("skill-1", "claude", ConflictResolution.KEEP_CANONICAL),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )
        assertTrue(planResult.plan.warnings.isEmpty())

        val syncResult = service.execute(planResult, mapOf("claude" to target), SkillScope.GLOBAL, null, root.resolve("backups"))

        assertEquals(SyncOperationStatus.SUCCESS, syncResult.status)
        assertEquals("canonical content", Files.readString(targetPath.resolve("SKILL.md")))
    }

    @Test
    fun `ResolveConflict KEEP_TARGET replaces canonical with the target's content and leaves the target untouched`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "canonical content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = root.resolve("claude-root")
        val targetPath = writeSkillMd(targetRoot.resolve("canonical"), "conflicting content")
        val target = FakeTarget("claude", targetRoot)

        val planResult = service.plan(
            SkillSyncRequest.ResolveConflict("skill-1", "claude", ConflictResolution.KEEP_TARGET),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )
        assertTrue(planResult.plan.warnings.isEmpty())

        val syncResult = service.execute(planResult, mapOf("claude" to target), SkillScope.GLOBAL, null, root.resolve("backups"))

        assertEquals(SyncOperationStatus.SUCCESS, syncResult.status)
        assertEquals("conflicting content", Files.readString(canonicalDir.resolve("SKILL.md")))
        assertEquals("conflicting content", Files.readString(targetPath.resolve("SKILL.md")))
    }

    @Test
    fun `ResolveConflict KEEP_TARGET warns about other managed agents that now need a resync`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "canonical content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = root.resolve("claude-root")
        writeSkillMd(targetRoot.resolve("canonical"), "conflicting content")
        val target = FakeTarget("claude", targetRoot)
        val ownershipStore = InMemorySyncOwnershipStore()
        ownershipStore.record(
            "skill-1",
            ManagedTarget("codex", "/codex/canonical", SkillSyncMode.COPY, EffectiveSyncMode.COPY, "canonical content"),
        )
        val service = SkillSyncService(ownershipStore = ownershipStore)

        val planResult = service.plan(
            SkillSyncRequest.ResolveConflict("skill-1", "claude", ConflictResolution.KEEP_TARGET),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertEquals(1, planResult.plan.warnings.size)
        assertTrue(planResult.plan.warnings.single().message.contains("codex"))
    }

    @Test
    fun `ResolveConflict CANCEL produces no steps, just an informational note`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "canonical content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = root.resolve("claude-root")
        writeSkillMd(targetRoot.resolve("canonical"), "conflicting content")
        val target = FakeTarget("claude", targetRoot)

        val result = service.plan(
            SkillSyncRequest.ResolveConflict("skill-1", "claude", ConflictResolution.CANCEL),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.isEmpty())
        assertEquals(1, result.plan.warnings.size)
        assertTrue(result.plan.warnings.single().message.contains("Cancelled"))
    }

    @ParameterizedTest(name = "{0}")
    @NullSource
    @ValueSource(strings = ["../escaped", ".", "..", "<absolute>", "canonical-mine"])
    fun `ResolveConflict KEEP_BOTH refuses a missing, unsafe or already used name and changes nothing`(requestedName: String?) {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "canonical content")
        val targetRoot = root.resolve("claude-root")
        writeSkillMd(targetRoot.resolve("canonical"), "conflicting content")
        writeSkillMd(targetRoot.resolve("canonical-mine"), "already here")
        val newName = if (requestedName == "<absolute>") root.resolve("absolute-copy").toAbsolutePath().toString() else requestedName

        val result = service.plan(
            SkillSyncRequest.ResolveConflict("skill-1", "claude", ConflictResolution.KEEP_BOTH, newName),
            agentSkill(sharedPath = canonicalDir),
            "op-1",
            mapOf("claude" to FakeTarget("claude", targetRoot)),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.isEmpty(), "must reject '$requestedName'")
        if (requestedName == null) assertEquals(1, result.plan.warnings.size, "warns instead of guessing a name")
        assertEquals("already here", Files.readString(targetRoot.resolve("canonical-mine").resolve("SKILL.md")), "Must not be clobbered")
        assertFalse(Files.exists(root.resolve("escaped")))
        assertFalse(Files.exists(root.resolve("absolute-copy")))
    }

    @Test
    fun `ResolveConflict KEEP_BOTH copies the diverged target under the new name, leaving the original and canonical untouched`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "canonical content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = root.resolve("claude-root")
        val originalTargetPath = writeSkillMd(targetRoot.resolve("canonical"), "conflicting content")
        val target = FakeTarget("claude", targetRoot)

        val planResult = service.plan(
            SkillSyncRequest.ResolveConflict("skill-1", "claude", ConflictResolution.KEEP_BOTH, newDirectoryName = "canonical-mine"),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )
        val result = service.execute(planResult, mapOf("claude" to target), SkillScope.GLOBAL, null, root.resolve("backups"))

        assertEquals(SyncOperationStatus.SUCCESS, result.status)
        val newPath = targetRoot.resolve("canonical-mine")
        assertEquals("conflicting content", Files.readString(newPath.resolve("SKILL.md")))
        assertEquals("conflicting content", Files.readString(originalTargetPath.resolve("SKILL.md")), "The original conflicting target must be untouched")
        assertEquals("canonical content", Files.readString(canonicalDir.resolve("SKILL.md")), "The canonical must be untouched")
    }

    @Test
    fun `ReplaceCopy overwrites one agent's copy with another version behind a backup, also for an agent that reads the shared folder`() {
        val userHome = Files.createDirectories(root.resolve("home"))
        val source = writeSkillMd(userHome.resolve(".claude").resolve("skills").resolve("url-checker"), "claude version")
        val targetPath = writeSkillMd(userHome.resolve(".gemini").resolve("config").resolve("skills").resolve("url-checker"), "antigravity version")
        val target = AntigravitySkillSyncTarget(userHome)
        val service = promoteService(userHome)

        val planResult = service.plan(
            SkillSyncRequest.ReplaceCopy("skill-1", source, "antigravity", targetPath, SkillScope.GLOBAL),
            agentSkill(),
            "op-1",
            mapOf("antigravity" to target),
            setOf("antigravity"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(planResult.plan.steps.isNotEmpty(), planResult.plan.warnings.toString())
        val result = service.execute(planResult, mapOf("antigravity" to target), SkillScope.GLOBAL, null, root.resolve("backups"))

        assertEquals(SyncOperationStatus.SUCCESS, result.status)
        assertEquals("claude version", Files.readString(targetPath.resolve("SKILL.md")))
        assertEquals("claude version", Files.readString(source.resolve("SKILL.md")), "the source stays untouched")
        assertTrue(Files.walk(root.resolve("backups")).use { paths -> paths.anyMatch { it.fileName.toString() == "SKILL.md" } }, "the replaced copy is backed up")
    }

    @Test
    fun `ReplaceCopy refuses identical copies and a copy that is not where the agent keeps the skill`() {
        val userHome = Files.createDirectories(root.resolve("home"))
        val source = writeSkillMd(userHome.resolve(".claude").resolve("skills").resolve("url-checker"), "same")
        val identical = writeSkillMd(userHome.resolve(".gemini").resolve("config").resolve("skills").resolve("url-checker"), "same")
        val elsewhere = writeSkillMd(userHome.resolve("somewhere").resolve("url-checker"), "different")
        val target = AntigravitySkillSyncTarget(userHome)
        val service = promoteService(userHome)

        fun plan(targetPath: Path) = service.plan(
            SkillSyncRequest.ReplaceCopy("skill-1", source, "antigravity", targetPath, SkillScope.GLOBAL),
            agentSkill(),
            "op-1",
            mapOf("antigravity" to target),
            setOf("antigravity"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(plan(identical).plan.steps.isEmpty())
        assertTrue(plan(elsewhere).plan.steps.isEmpty())
        assertEquals("same", Files.readString(identical.resolve("SKILL.md")))
    }

    @Test
    fun `PromoteSkill works from an agent that also reads the shared directory natively`() {
        val userHome = Files.createDirectories(root.resolve("home"))
        val sourcePath = writeSkillMd(userHome.resolve(".gemini").resolve("config").resolve("skills").resolve("url-checker"), "content")
        val target = AntigravitySkillSyncTarget(userHome)
        val service = promoteService(userHome)

        val planResult = service.plan(
            SkillSyncRequest.PromoteSkill("skill-1", "antigravity", sourcePath, SkillScope.GLOBAL),
            agentSkill(),
            "op-1",
            mapOf("antigravity" to target),
            setOf("antigravity"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(planResult.plan.warnings.isEmpty(), planResult.plan.warnings.toString())
        assertTrue(planResult.plan.steps.isNotEmpty())
    }

    @Test
    fun `PromoteSkill creates the canonical directory and replaces the source with a link`() {
        val userHome = Files.createDirectories(root.resolve("home"))
        val sourcePath = writeSkillMd(userHome.resolve(".claude").resolve("skills").resolve("php-review"), "content")
        val target = ClaudeSkillSyncTarget(userHome)
        val skill = agentSkill()
        val service = promoteService(userHome)

        val planResult = service.plan(
            SkillSyncRequest.PromoteSkill(
                skillId = "skill-1",
                sourceAgentId = "claude",
                sourcePath = sourcePath,
                scope = SkillScope.GLOBAL,
            ),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(planResult.plan.warnings.isEmpty())
        assertEquals(8, planResult.plan.steps.size)

        val syncResult = service.execute(
            planResult,
            mapOf("claude" to target),
            SkillScope.GLOBAL,
            null,
            root.resolve("backups"),
        )

        assertEquals(SyncOperationStatus.SUCCESS, syncResult.status)
        val canonicalPath = userHome.resolve(".agents").resolve("skills").resolve("php-review")
        assertTrue(Files.exists(canonicalPath))
        assertEquals("content", Files.readString(canonicalPath.resolve("SKILL.md")))
        assertEquals("content", Files.readString(sourcePath.resolve("SKILL.md")))
    }

    @Test
    fun `backupBeforeReplacement = false skips PromoteSkill's BackupExisting step but still replaces the source with a link`() {
        val userHome = Files.createDirectories(root.resolve("home"))
        val sourcePath = writeSkillMd(userHome.resolve(".claude").resolve("skills").resolve("php-review"), "content")
        val target = ClaudeSkillSyncTarget(userHome)
        val skill = agentSkill()
        val service = promoteService(userHome)

        val planResult = service.plan(
            SkillSyncRequest.PromoteSkill(
                skillId = "skill-1",
                sourceAgentId = "claude",
                sourcePath = sourcePath,
                scope = SkillScope.GLOBAL,
            ),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
            backupBeforeReplacement = false,
        )

        assertTrue(planResult.plan.steps.none { it is SkillSyncStep.BackupExisting })
        assertEquals(7, planResult.plan.steps.size, "one fewer than the default 8-step plan")

        val syncResult = service.execute(planResult, mapOf("claude" to target), SkillScope.GLOBAL, null, root.resolve("backups"))

        assertEquals(SyncOperationStatus.SUCCESS, syncResult.status)
        assertEquals("content", Files.readString(sourcePath.resolve("SKILL.md")), "still replaced with a link back to the canonical copy")
    }

    @Test
    fun `PromoteSkill honors a requested copy mode instead of always preferring a link`() {
        val userHome = Files.createDirectories(root.resolve("home"))
        val sourcePath = writeSkillMd(userHome.resolve(".claude").resolve("skills").resolve("php-review"), "content")
        val target = ClaudeSkillSyncTarget(userHome)
        val skill = agentSkill()
        val service = promoteService(userHome)

        val planResult = service.plan(
            SkillSyncRequest.PromoteSkill(
                skillId = "skill-1",
                sourceAgentId = "claude",
                sourcePath = sourcePath,
                scope = SkillScope.GLOBAL,
                mode = SkillSyncMode.COPY,
            ),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(planResult.plan.warnings.isEmpty(), "Explicitly requesting copy mode is not a linking-unavailable warning")
        assertTrue(planResult.plan.steps.none { it is SkillSyncStep.CreateLink })
        val metadata = planResult.plan.steps.filterIsInstance<SkillSyncStep.WriteMetadata>().single()
        assertEquals(EffectiveSyncMode.COPY, metadata.mode)
        assertEquals(SkillSyncMode.COPY, metadata.requestedMode)
    }

    @Test
    fun `failed PromoteSkill removes the newly created canonical and leaves the source untouched`() {
        val userHome = Files.createDirectories(root.resolve("home"))
        val sourcePath = writeSkillMd(userHome.resolve(".claude").resolve("skills").resolve("php-review"), "content")
        val target = ClaudeSkillSyncTarget(userHome)
        val service = promoteService(userHome)
        val plan = service.plan(
            SkillSyncRequest.PromoteSkill("skill-1", "claude", sourcePath, SkillScope.GLOBAL),
            agentSkill(),
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )
        val backupRoot = root.resolve("backups")
        val blockedBackupPath = backupRoot.resolve("op-1").resolve("claude").resolve("skill-1")
        Files.createDirectories(blockedBackupPath.parent)
        Files.writeString(blockedBackupPath, "blocks backup directory creation")

        val result = service.execute(plan, mapOf("claude" to target), SkillScope.GLOBAL, null, backupRoot)

        assertEquals(SyncOperationStatus.FAILED, result.status)
        assertFalse(Files.exists(userHome.resolve(".agents").resolve("skills").resolve("php-review")))
        assertEquals("content", Files.readString(sourcePath.resolve("SKILL.md")))
    }

    @Test
    fun `PromoteSkill returns a warning when the source skill is not found`() {
        val userHome = Files.createDirectories(root.resolve("home"))
        val sourcePath = userHome.resolve(".claude").resolve("skills").resolve("missing")
        val target = ClaudeSkillSyncTarget(userHome)
        val skill = agentSkill()
        val service = promoteService(userHome)

        val result = service.plan(
            SkillSyncRequest.PromoteSkill("skill-1", "claude", sourcePath, SkillScope.GLOBAL),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.isEmpty())
        assertEquals(1, result.plan.warnings.size)
    }

    @Test
    fun `PromoteSkill refuses to overwrite an existing canonical`() {
        val userHome = Files.createDirectories(root.resolve("home"))
        val sourcePath = writeSkillMd(userHome.resolve(".claude").resolve("skills").resolve("php-review"), "content")
        writeSkillMd(userHome.resolve(".agents").resolve("skills").resolve("php-review"), "already there")
        val target = ClaudeSkillSyncTarget(userHome)
        val skill = agentSkill()
        val service = promoteService(userHome)

        val result = service.plan(
            SkillSyncRequest.PromoteSkill("skill-1", "claude", sourcePath, SkillScope.GLOBAL),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.isEmpty())
        assertEquals(1, result.plan.warnings.size)
    }

    @Test
    fun `PromoteSkill with alsoShareWith still promotes and notes the pending shares`() {
        val userHome = Files.createDirectories(root.resolve("home"))
        val sourcePath = writeSkillMd(userHome.resolve(".claude").resolve("skills").resolve("php-review"), "content")
        val target = ClaudeSkillSyncTarget(userHome)
        val skill = agentSkill()
        val service = promoteService(userHome)

        val result = service.plan(
            SkillSyncRequest.PromoteSkill("skill-1", "claude", sourcePath, SkillScope.GLOBAL, alsoShareWith = setOf("codex")),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.isNotEmpty())
        assertEquals(1, result.plan.warnings.size)
        assertTrue(result.plan.warnings.single().message.contains("codex"))
        // The actual chaining (rediscover + ShareSkill per target) is orchestrated one layer up,
        // in SkillSyncApplicationService.execute() - see SkillSyncApplicationServiceTest.
    }

    @Test
    fun `PromoteSkill returns a warning when the source agent has no sync target`() {
        val userHome = Files.createDirectories(root.resolve("home"))
        val sourcePath = writeSkillMd(userHome.resolve(".claude").resolve("skills").resolve("php-review"), "content")
        val skill = agentSkill()
        val service = promoteService(userHome)

        val result = service.plan(
            SkillSyncRequest.PromoteSkill("skill-1", "claude", sourcePath, SkillScope.GLOBAL),
            skill,
            "op-1",
            emptyMap(),
            emptySet(),
            SkillScope.GLOBAL,
            null,
        )

        assertTrue(result.plan.steps.isEmpty())
        assertEquals(1, result.plan.warnings.size)
    }

    @Test
    fun `execute delegates to the executor, applies the plan, and records an audit entry`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = root.resolve("claude-root")
        val targetPath = targetRoot.resolve("canonical")
        val target = FakeTarget("claude", targetRoot)
        val auditTrail = InMemorySyncAuditTrail()
        val service = SkillSyncService(auditTrail = auditTrail)

        val planResult = service.plan(
            SkillSyncRequest.ShareSkill(skillId = "skill-1", targetAgentId = "claude"),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        val syncResult = service.execute(
            planResult,
            mapOf("claude" to target),
            SkillScope.GLOBAL,
            null,
            root.resolve("backups"),
        )

        assertEquals(SyncOperationStatus.SUCCESS, syncResult.status)
        assertTrue(Files.exists(targetPath))
        assertEquals("content", Files.readString(targetPath.resolve("SKILL.md")))

        val entry = auditTrail.entriesFor("skill-1").single()
        assertEquals("op-1", entry.operationId)
        assertEquals(SyncAction.SHARE, entry.action)
        assertEquals(setOf("claude"), entry.affectedAgents)
        assertEquals(SyncOperationStatus.SUCCESS, entry.result)
    }

    @Test
    fun `a failed execute still records an audit entry with FAILED status`() {
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val targetRoot = root.resolve("claude-root")
        val targetPath = targetRoot.resolve("canonical")
        val target = FakeTarget("claude", targetRoot)
        val auditTrail = InMemorySyncAuditTrail()
        val service = SkillSyncService(auditTrail = auditTrail)

        val planResult = service.plan(
            SkillSyncRequest.ShareSkill(skillId = "skill-1", targetAgentId = "claude"),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )
        // Drifts the target after the plan was built, forcing revalidation to abort it.
        writeSkillMd(targetPath, "unexpected content placed after the preview")

        val syncResult = service.execute(planResult, mapOf("claude" to target), SkillScope.GLOBAL, null, root.resolve("backups"))

        assertEquals(SyncOperationStatus.FAILED, syncResult.status)
        val entry = auditTrail.entriesFor("skill-1").single()
        assertEquals(SyncAction.SHARE, entry.action)
        assertEquals(SyncOperationStatus.FAILED, entry.result)
    }

    @Test
    fun `ShareSkill against a real adapter nests the target under its skills root, not equal to it`() {
        // Regression guard for the root-vs-leaf bug: unlike FakeTarget above, ClaudeSkillSyncTarget
        // is the real production adapter, and its globalSkillDirectory() is genuinely a root
        // (userHome/.claude/skills), not a specific skill's directory.
        val canonicalDir = writeSkillMd(root.resolve("canonical"), "content")
        val skill = agentSkill(sharedPath = canonicalDir)
        val userHome = Files.createDirectories(root.resolve("home"))
        val realTarget = ClaudeSkillSyncTarget(userHome)
        val expectedTargetPath = userHome.resolve(".claude").resolve("skills").resolve("canonical")

        val result = service.plan(
            SkillSyncRequest.ShareSkill(skillId = "skill-1", targetAgentId = "claude"),
            skill,
            "op-1",
            mapOf("claude" to realTarget),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )

        val createLink = result.plan.steps.filterIsInstance<SkillSyncStep.CreateLink>().single()
        assertEquals(expectedTargetPath, createLink.target)
        assertTrue(createLink.target != realTarget.globalSkillDirectory())
    }

    /**
     * [SkillSyncService]'s default [SharedSkillProvider] resolves against the real system
     * `user.home` — PromoteSkill tests must inject one scoped to the test's own fake home so the
     * "does a canonical already exist" check never touches the real filesystem.
     */
    private fun promoteService(userHome: Path) = SkillSyncService(sharedSkillDirectory = SharedSkillProvider(userHome))

    private fun agentSkill(sharedPath: Path? = null, agentOnlyPath: Path? = null): AgentSkill {
        val sources = buildList {
            sharedPath?.let { add(SkillSource(agentId = null, path = it.toString(), scope = SkillScope.GLOBAL, shared = true, fingerprint = "fixture")) }
            agentOnlyPath?.let { add(SkillSource(agentId = "claude", path = it.toString(), scope = SkillScope.GLOBAL, shared = false, fingerprint = "fixture")) }
        }
        return AgentSkill(
            identity = SkillIdentity("skill-1"),
            name = "review",
            description = null,
            scope = SkillScope.GLOBAL,
            sources = sources,
            compatibleAgents = emptySet(),
            consistency = SkillConsistency.SINGLE_SOURCE,
        )
    }

    private class FakeTarget(
        override val agentId: String,
        private val global: Path,
    ) : SkillSyncTarget {
        override fun globalSkillDirectory(): Path = global

        override fun projectSkillDirectory(project: DiscoveredProject): Path? = null

        override fun supportsLinkedSkills(): Boolean = true
    }
}
