package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.link.FileLinkStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.LinkResult
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BackupServiceTest {
    @TempDir
    lateinit var root: Path

    private val service = BackupService()

    @Test
    fun `backup copies the directory content faithfully`() {
        val original = Files.createDirectory(root.resolve("original"))
        Files.writeString(original.resolve("SKILL.md"), "content")
        val backupRoot = root.resolve("backups")

        val backup = service.backup("claude", "skill-1", original, backupRoot, "op-1")

        assertNotNull(backup)
        assertEquals("content", Files.readString(backup!!.backupPath.resolve("SKILL.md")))
        assertEquals(original, backup.originalPath)
    }

    @Test
    fun `restore puts the backup back exactly`() {
        val original = Files.createDirectory(root.resolve("original"))
        Files.writeString(original.resolve("SKILL.md"), "content")
        val backupRoot = root.resolve("backups")
        val backup = service.backup("claude", "skill-1", original, backupRoot, "op-1")!!

        Files.writeString(original.resolve("SKILL.md"), "mutated after backup")

        val restored = service.restore(backup)

        assertTrue(restored)
        assertEquals("content", Files.readString(original.resolve("SKILL.md")))
    }

    @Test
    fun `backup writes a metadata sidecar that BackupMetadataStore can read back`() {
        val original = Files.createDirectory(root.resolve("original"))
        Files.writeString(original.resolve("SKILL.md"), "content")
        val backupRoot = root.resolve("backups")
        val instanceKey = SkillInstanceKey("host", SkillScope.GLOBAL, "/shared", "skill-1")

        val backup = service.backup("claude", "skill-1", original, backupRoot, "op-1", instanceKey = instanceKey)!!

        val records = BackupMetadataStore.listBackups(backupRoot)
        val record = records.single()
        assertEquals(instanceKey, record.instanceKey)
        assertEquals("claude", record.agentId)
        assertEquals(backup.backupPath, record.backup.backupPath)
        assertEquals(backup.originalPath, record.backup.originalPath)
        assertEquals(backup.representation, record.backup.representation)
    }

    @Test
    fun `backing up a path that does not exist returns null`() {
        val missing = root.resolve("does-not-exist")

        val backup = service.backup("claude", "skill-1", missing, root.resolve("backups"), "op-1")

        assertNull(backup)
    }

    @Test
    fun `restoring after the original was removed still succeeds`() {
        val original = Files.createDirectory(root.resolve("original"))
        Files.writeString(original.resolve("SKILL.md"), "content")
        val backupRoot = root.resolve("backups")
        val backup = service.backup("claude", "skill-1", original, backupRoot, "op-1")!!

        DirectoryDeleter.deleteRecursively(original)
        assertFalse(Files.exists(original))

        val restored = service.restore(backup)

        assertTrue(restored)
        assertEquals("content", Files.readString(original.resolve("SKILL.md")))
    }

    @Test
    fun `failed staged restore leaves the current content unchanged`() {
        val original = Files.createDirectory(root.resolve("original"))
        Files.writeString(original.resolve("SKILL.md"), "backup content")
        val backup = service.backup("claude", "skill-1", original, root.resolve("backups"), "op-1")!!
        Files.writeString(original.resolve("SKILL.md"), "current content")
        val failingRestore = BackupService(
            restoreCopyStrategy = object : FileLinkStrategy {
                override fun canLink(source: Path, target: Path): Boolean = true
                override fun createLink(source: Path, target: Path): LinkResult = LinkResult.Failure("disk full")
            },
        )

        val restored = failingRestore.restore(backup)

        assertFalse(restored)
        assertEquals("current content", Files.readString(original.resolve("SKILL.md")))
    }
}
