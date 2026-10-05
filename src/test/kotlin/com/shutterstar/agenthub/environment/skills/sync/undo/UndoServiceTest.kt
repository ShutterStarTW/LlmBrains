package com.shutterstar.agenthub.environment.skills.sync.undo

import com.shutterstar.agenthub.writeSkillMd
import com.shutterstar.agenthub.environment.skills.discovery.SharedSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SkillFingerprint
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.environment.skills.sync.SkillSyncEngine
import com.shutterstar.agenthub.environment.skills.sync.execution.BackupService
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncRequest
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncResult
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStep
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStepResult
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import com.shutterstar.agenthub.environment.skills.sync.ownership.InMemorySyncOwnershipStore
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.environment.skills.sync.target.ClaudeSkillSyncTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class UndoServiceTest {
    @TempDir
    lateinit var root: Path

    private val undoService = UndoService()

    @Test
    fun `a CreateLink step with a matching backup is restored to its pre-sync content`() {
        val backupRoot = root.resolve("backups")
        val original = writeSkillMd(root.resolve("target"), "old content")
        val backupService = BackupService()
        val backup = backupService.backup("claude", "skill-1", original, backupRoot, "op-1")
        checkNotNull(backup)
        writeSkillMd(original, "new content")

        val step = SkillSyncStep.CreateLink(
            agentId = "claude",
            source = root.resolve("canonical"),
            target = original,
            requestedMode = SkillSyncMode.SYMLINK,
            effectiveMode = EffectiveSyncMode.SYMLINK,
        )
        val result = SkillSyncResult(
            operationId = "op-1",
            status = SyncOperationStatus.SUCCESS,
            appliedSteps = listOf(
                SkillSyncStepResult(step, succeeded = true),
                SkillSyncStepResult(
                    SkillSyncStep.VerifyFingerprint("claude", original, SkillFingerprint().calculate(original)!!),
                    succeeded = true,
                ),
            ),
            errors = emptyList(),
            rollbackAvailable = true,
            restorableBackups = listOf(backup),
        )

        val undoResult = undoService.undo(result)

        assertEquals(listOf(original), undoResult.restoredPaths)
        assertTrue(undoResult.removedPaths.isEmpty())
        assertTrue(undoResult.errors.isEmpty())
        assertEquals("old content", Files.readString(original.resolve("SKILL.md")))
    }

    @Test
    fun `a CopySkill step with no backup is removed`() {
        val canonicalPath = writeSkillMd(root.resolve("canonical"), "content")

        val step = SkillSyncStep.CopySkill(agentId = "claude", source = root.resolve("source"), target = canonicalPath)
        val result = SkillSyncResult(
            operationId = "op-1",
            status = SyncOperationStatus.SUCCESS,
            appliedSteps = listOf(
                SkillSyncStepResult(step, succeeded = true),
                SkillSyncStepResult(
                    SkillSyncStep.VerifyFingerprint("claude", canonicalPath, SkillFingerprint().calculate(canonicalPath)!!),
                    succeeded = true,
                ),
            ),
            errors = emptyList(),
            rollbackAvailable = false,
            restorableBackups = emptyList(),
        )

        val undoResult = undoService.undo(result)

        assertEquals(listOf(canonicalPath), undoResult.removedPaths)
        assertTrue(undoResult.restoredPaths.isEmpty())
        assertTrue(undoResult.errors.isEmpty())
        assertFalse(Files.exists(canonicalPath))
    }

    @Test
    fun `undo is a no-op when no applied step touched a reversible path`() {
        val step = SkillSyncStep.RemoveExisting(agentId = "claude", path = root.resolve("target"))
        val result = SkillSyncResult(
            operationId = "op-1",
            status = SyncOperationStatus.SUCCESS,
            appliedSteps = listOf(SkillSyncStepResult(step, succeeded = true)),
            errors = emptyList(),
            rollbackAvailable = false,
            restorableBackups = emptyList(),
        )

        val undoResult = undoService.undo(result)

        assertTrue(undoResult.restoredPaths.isEmpty())
        assertTrue(undoResult.removedPaths.isEmpty())
        assertTrue(undoResult.errors.isEmpty())
    }

    @Test
    fun `undoing a PromoteSkill result removes the new canonical and restores the original source`() {
        val userHome = Files.createDirectories(root.resolve("home"))
        val sourcePath = writeSkillMd(userHome.resolve(".claude").resolve("skills").resolve("php-review"), "original content")
        val target = ClaudeSkillSyncTarget(userHome)
        val skill = AgentSkill(
            identity = SkillIdentity("skill-1"),
            name = "php-review",
            description = null,
            scope = SkillScope.GLOBAL,
            sources = emptyList(),
            compatibleAgents = emptySet(),
            consistency = SkillConsistency.SINGLE_SOURCE,
        )
        val service = SkillSyncEngine(sharedSkillDirectory = SharedSkillProvider(userHome))

        val planResult = service.planner.plan(
            SkillSyncRequest.PromoteSkill("skill-1", "claude", sourcePath, SkillScope.GLOBAL),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )
        val syncResult = service.runner.execute(planResult, mapOf("claude" to target), SkillScope.GLOBAL, null, root.resolve("backups"))
        assertEquals(SyncOperationStatus.SUCCESS, syncResult.status)
        val canonicalPath = userHome.resolve(".agents").resolve("skills").resolve("php-review")
        assertTrue(Files.exists(canonicalPath))

        val undoResult = service.runner.undo(syncResult)

        assertTrue(undoResult.errors.isEmpty())
        assertFalse(Files.exists(canonicalPath))
        assertTrue(Files.exists(sourcePath))
        assertEquals("original content", Files.readString(sourcePath.resolve("SKILL.md")))
    }

    @Test
    fun `undo refuses to remove promoted content that changed after synchronization`() {
        val userHome = Files.createDirectories(root.resolve("home"))
        val sourcePath = writeSkillMd(userHome.resolve(".claude").resolve("skills").resolve("php-review"), "original content")
        val target = ClaudeSkillSyncTarget(userHome)
        val skill = agentSkill(emptyList())
        val service = SkillSyncEngine(sharedSkillDirectory = SharedSkillProvider(userHome))
        val plan = service.planner.plan(
            SkillSyncRequest.PromoteSkill("skill-1", "claude", sourcePath, SkillScope.GLOBAL),
            skill,
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )
        val syncResult = service.runner.execute(plan, mapOf("claude" to target), SkillScope.GLOBAL, null, root.resolve("backups"))
        val canonicalPath = userHome.resolve(".agents").resolve("skills").resolve("php-review")
        Files.writeString(canonicalPath.resolve("SKILL.md"), "changed after sync")

        val undoResult = service.runner.undo(syncResult)

        assertTrue(undoResult.errors.isNotEmpty())
        assertEquals("changed after sync", Files.readString(canonicalPath.resolve("SKILL.md")))
        assertEquals("changed after sync", Files.readString(sourcePath.resolve("SKILL.md")))
    }

    @Test
    fun `undoing a new managed copy removes its ownership record`() {
        val userHome = Files.createDirectories(root.resolve("home"))
        val canonicalPath = writeSkillMd(userHome.resolve(".agents").resolve("skills").resolve("php-review"), "content")
        val target = ClaudeSkillSyncTarget(userHome)
        val ownershipStore = InMemorySyncOwnershipStore()
        val service = SkillSyncEngine(
            ownershipStore = ownershipStore,
            sharedSkillDirectory = SharedSkillProvider(userHome),
        )
        val plan = service.planner.plan(
            SkillSyncRequest.ShareSkill("skill-1", "claude", SkillSyncMode.COPY),
            agentSkill(listOf(SkillSource(null, canonicalPath.toString(), SkillScope.GLOBAL, shared = true, fingerprint = "fixture"))),
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )
        val syncResult = service.runner.execute(plan, mapOf("claude" to target), SkillScope.GLOBAL, null, root.resolve("backups"))
        assertTrue(ownershipStore.managedTarget("skill-1", "claude") != null)

        val undoResult = service.runner.undo(syncResult)

        assertTrue(undoResult.errors.isEmpty())
        assertNull(ownershipStore.managedTarget("skill-1", "claude"))
        assertFalse(Files.exists(userHome.resolve(".claude").resolve("skills").resolve("php-review")))
    }

    @Test
    fun `undoing StopSharing restores both the target and its ownership record`() {
        val userHome = Files.createDirectories(root.resolve("home"))
        val canonicalPath = writeSkillMd(userHome.resolve(".agents").resolve("skills").resolve("php-review"), "content")
        val targetPath = writeSkillMd(userHome.resolve(".claude").resolve("skills").resolve("php-review"), "content")
        val target = ClaudeSkillSyncTarget(userHome)
        val ownershipStore = InMemorySyncOwnershipStore()
        val managedTarget = ManagedTarget(
            "claude",
            targetPath.toString(),
            SkillSyncMode.COPY,
            EffectiveSyncMode.COPY,
            SkillFingerprint().calculate(targetPath),
        )
        ownershipStore.record("skill-1", managedTarget)
        val service = SkillSyncEngine(
            ownershipStore = ownershipStore,
            sharedSkillDirectory = SharedSkillProvider(userHome),
        )
        val plan = service.planner.plan(
            SkillSyncRequest.StopSharing("skill-1", "claude"),
            agentSkill(listOf(SkillSource(null, canonicalPath.toString(), SkillScope.GLOBAL, shared = true, fingerprint = "fixture"))),
            "op-1",
            mapOf("claude" to target),
            setOf("claude"),
            SkillScope.GLOBAL,
            null,
        )
        val syncResult = service.runner.execute(plan, mapOf("claude" to target), SkillScope.GLOBAL, null, root.resolve("backups"))
        assertFalse(Files.exists(targetPath))
        assertNull(ownershipStore.managedTarget("skill-1", "claude"))

        val undoResult = service.runner.undo(syncResult)

        assertTrue(undoResult.errors.isEmpty())
        assertEquals("content", Files.readString(targetPath.resolve("SKILL.md")))
        assertEquals(managedTarget, ownershipStore.managedTarget("skill-1", "claude"))
    }

    private fun agentSkill(sources: List<SkillSource>) = AgentSkill(
        identity = SkillIdentity("skill-1"),
        name = "php-review",
        description = null,
        scope = SkillScope.GLOBAL,
        sources = sources,
        compatibleAgents = emptySet(),
        consistency = SkillConsistency.SINGLE_SOURCE,
    )
}
