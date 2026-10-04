package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.writeSkillMd
import com.shutterstar.agenthub.OsDetector
import com.shutterstar.agenthub.environment.skills.discovery.SkillFingerprint
import com.shutterstar.agenthub.environment.skills.sync.link.CopyStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.FileLinkStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.LinkResult
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStep
import com.shutterstar.agenthub.environment.skills.sync.ownership.InMemorySyncOwnershipStore
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class SkillSyncStepExecutorTest {
    @TempDir
    lateinit var root: Path

    private val ownershipStore = InMemorySyncOwnershipStore()
    private val executor = SkillSyncStepExecutor(ownershipStore = ownershipStore)
    private val fingerprint = SkillFingerprint()

    private fun context() = StepExecutionContext(operationId = "op-1", skillId = "skill-1", backupRoot = root.resolve("backups"))

    @Test
    fun `CreateDirectory creates nested directories`() {
        val path = root.resolve("a").resolve("b").resolve("c")

        val outcome = executor.execute(SkillSyncStep.CreateDirectory("claude", path), context())

        assertTrue(outcome is StepOutcome.Success)
        assertTrue(Files.isDirectory(path))
    }

    @Test
    fun `BackupExisting produces a SkillBackup with matching content`() {
        val target = writeSkillMd(root.resolve("target"), "content")

        val outcome = executor.execute(SkillSyncStep.BackupExisting("claude", target), context())

        assertTrue(outcome is StepOutcome.Success)
        val backup = (outcome as StepOutcome.Success).backup
        assertTrue(backup != null)
        assertEquals("content", Files.readString(backup!!.backupPath.resolve("SKILL.md")))
    }

    @Test
    fun `RemoveExisting deletes a plain directory recursively`() {
        val target = writeSkillMd(root.resolve("target"), "content")

        val outcome = executor.execute(SkillSyncStep.RemoveExisting("claude", target), context())

        assertTrue(outcome is StepOutcome.Success)
        assertFalse(Files.exists(target))
    }

    @Test
    fun `RemoveExisting clears any recorded ownership for that skill and agent`() {
        val target = writeSkillMd(root.resolve("target"), "content")
        ownershipStore.record(
            "skill-1",
            ManagedTarget("claude", target.toString(), SkillSyncMode.COPY, EffectiveSyncMode.COPY, "content"),
        )

        executor.execute(SkillSyncStep.RemoveExisting("claude", target), context())

        assertEquals(null, ownershipStore.managedTarget("skill-1", "claude"))
    }

    @Test
    fun `RemoveExisting deletes a symlink without touching its target`() {
        val source = writeSkillMd(root.resolve("source"), "content")
        val link = root.resolve("link")
        val linked = runCatching { Files.createSymbolicLink(link, source) }.isSuccess
        assumeTrue(linked, "symlink creation requires elevated privilege on this machine")

        val outcome = executor.execute(SkillSyncStep.RemoveExisting("claude", link), context())

        assertTrue(outcome is StepOutcome.Success)
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(source))
        assertEquals("content", Files.readString(source.resolve("SKILL.md")))
    }

    @Test
    fun `CreateLink with SYMLINK effective mode creates a real symlink`() {
        assumeFalse(OsDetector.isWindows())
        val source = writeSkillMd(root.resolve("source"), "content")
        val target = root.resolve("target")

        val outcome = executor.execute(
            SkillSyncStep.CreateLink("claude", source, target, SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK),
            context(),
        )

        assertTrue(outcome is StepOutcome.Success)
        assertTrue(Files.isSymbolicLink(target))
    }

    @Test
    fun `CreateLink with JUNCTION effective mode creates a working junction`() {
        assumeTrue(OsDetector.isWindows())
        val source = writeSkillMd(root.resolve("source"), "content")
        val target = root.resolve("target")

        val outcome = executor.execute(
            SkillSyncStep.CreateLink("claude", source, target, SkillSyncMode.SYMLINK, EffectiveSyncMode.JUNCTION),
            context(),
        )

        assertTrue(outcome is StepOutcome.Success)
        assertEquals("content", Files.readString(target.resolve("SKILL.md")))
    }

    @Test
    fun `CopySkill copies the source content to the target`() {
        val source = writeSkillMd(root.resolve("source"), "content")
        val target = root.resolve("target")

        val outcome = executor.execute(SkillSyncStep.CopySkill("claude", source, target), context())

        assertTrue(outcome is StepOutcome.Success)
        assertEquals("content", Files.readString(target.resolve("SKILL.md")))
    }

    @Test
    fun `VerifyFingerprint succeeds when content matches`() {
        val target = writeSkillMd(root.resolve("target"), "content")
        val expected = fingerprint.calculate(target)!!

        val outcome = executor.execute(SkillSyncStep.VerifyFingerprint("claude", target, expected), context())

        assertTrue(outcome is StepOutcome.Success)
    }

    @Test
    fun `VerifyFingerprint fails when content differs`() {
        val target = writeSkillMd(root.resolve("target"), "different content")

        val outcome = executor.execute(SkillSyncStep.VerifyFingerprint("claude", target, "some-other-fingerprint"), context())

        assertTrue(outcome is StepOutcome.Failure)
    }

    @Test
    fun `WriteMetadata records a ManagedTarget with a derived requestedMode and a real fingerprint`() {
        val target = writeSkillMd(root.resolve("target"), "content")
        val expectedFingerprint = fingerprint.calculate(target)

        val outcome = executor.execute(
            SkillSyncStep.WriteMetadata("claude", "skill-1", target, EffectiveSyncMode.COPY),
            context(),
        )

        assertTrue(outcome is StepOutcome.Success)
        val recorded = ownershipStore.managedTarget("skill-1", "claude")
        assertEquals("claude", recorded?.agentId)
        assertEquals(target.toString(), recorded?.path)
        assertEquals(SkillSyncMode.COPY, recorded?.requestedMode)
        assertEquals(EffectiveSyncMode.COPY, recorded?.effectiveMode)
        assertEquals(expectedFingerprint, recorded?.lastFingerprint)
    }

    @Test
    fun `WriteMetadata derives a SYMLINK requestedMode for SYMLINK and JUNCTION effective modes`() {
        val target = writeSkillMd(root.resolve("target"), "content")

        executor.execute(SkillSyncStep.WriteMetadata("claude", "skill-1", target, EffectiveSyncMode.JUNCTION), context())

        assertEquals(SkillSyncMode.SYMLINK, ownershipStore.managedTarget("skill-1", "claude")?.requestedMode)
    }

    @Test
    fun `replaceExisting swaps content without deleting the live path first`() {
        val source = writeSkillMd(root.resolve("source"), "new")
        val target = writeSkillMd(root.resolve("target"), "old")
        ownershipStore.record(
            "skill-1",
            ManagedTarget("claude", target.toString(), SkillSyncMode.COPY, EffectiveSyncMode.COPY, "old"),
        )
        var originalPresentDuringInstall = false
        val trackingCopy = object : com.shutterstar.agenthub.environment.skills.sync.link.FileLinkStrategy {
            override fun canLink(source: Path, target: Path): Boolean = true
            override fun createLink(source: Path, target: Path): LinkResult {
                originalPresentDuringInstall = Files.exists(root.resolve("target"), LinkOption.NOFOLLOW_LINKS)
                return CopyStrategy().createLink(source, target)
            }
        }
        val replacingExecutor = SkillSyncStepExecutor(
            ownershipStore = ownershipStore,
            copyStrategy = trackingCopy,
        )

        val outcome = replacingExecutor.replaceExisting(
            SkillSyncStep.RemoveExisting("claude", target),
            SkillSyncStep.CopySkill("claude", source, target),
            context(),
        )

        assertTrue(outcome is StepOutcome.Success)
        assertTrue(originalPresentDuringInstall)
        assertEquals("new", Files.readString(target.resolve("SKILL.md")))
        assertEquals(null, ownershipStore.managedTarget("skill-1", "claude"))
    }

    @Test
    fun `replaceExisting failure leaves the original directory and ownership intact`() {
        val source = writeSkillMd(root.resolve("source"), "new")
        val target = writeSkillMd(root.resolve("target"), "old")
        ownershipStore.record(
            "skill-1",
            ManagedTarget("claude", target.toString(), SkillSyncMode.COPY, EffectiveSyncMode.COPY, "old"),
        )
        val failingExecutor = SkillSyncStepExecutor(
            ownershipStore = ownershipStore,
            copyStrategy = object : com.shutterstar.agenthub.environment.skills.sync.link.FileLinkStrategy {
                override fun canLink(source: Path, target: Path): Boolean = true
                override fun createLink(source: Path, target: Path): LinkResult = LinkResult.Failure("disk full")
            },
        )

        val outcome = failingExecutor.replaceExisting(
            SkillSyncStep.RemoveExisting("claude", target),
            SkillSyncStep.CopySkill("claude", source, target),
            context(),
        )

        assertTrue(outcome is StepOutcome.Failure)
        assertEquals("old", Files.readString(target.resolve("SKILL.md")))
        assertEquals("claude", ownershipStore.managedTarget("skill-1", "claude")?.agentId)
    }
}
