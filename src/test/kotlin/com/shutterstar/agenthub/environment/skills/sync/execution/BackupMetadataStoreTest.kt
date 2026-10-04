package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.OsDetector
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.link.LinkResult
import com.shutterstar.agenthub.environment.skills.sync.link.WindowsJunctionStrategy
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class BackupMetadataStoreTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `round-trips a link backup including its link target`() {
        val backup = SkillBackup(
            id = "op-1:claude",
            originalPath = Path.of("/agents/claude/skills/review"),
            backupPath = root.resolve("op-1").resolve("claude").resolve("skill-1"),
            createdAt = Instant.parse("2026-09-05T10:00:00Z"),
            operationId = "op-1",
            representation = EffectiveSyncMode.SYMLINK,
            linkTarget = Path.of("/shared/skills/review"),
        )
        val key = SkillInstanceKey("host", SkillScope.PROJECT, "/repo/project", "skill-1")

        Files.createDirectories(backup.backupPath)
        BackupMetadataStore.write(backup, key, "claude")

        val record = BackupMetadataStore.listBackups(root).single()
        assertEquals(key, record.instanceKey)
        assertEquals("claude", record.agentId)
        assertEquals(backup, record.backup)
    }

    @Test
    fun `skips an unreadable or malformed sidecar instead of throwing`() {
        val sidecar = root.resolve("op-1").resolve("claude").resolve("skill-1.meta")
        Files.createDirectories(sidecar.parent)
        Files.writeString(sidecar, "not a valid properties payload for our schema\u0000")

        assertTrue(BackupMetadataStore.listBackups(root).isEmpty())
    }

    @Test
    fun `does not scan through a Windows junction under the backup root`() {
        assumeTrue(OsDetector.isWindows())
        val backupRoot = Files.createDirectory(root.resolve("backups"))
        val outside = Files.createDirectory(root.resolve("outside"))
        val externalBackup = SkillBackup(
            id = "external:claude",
            originalPath = outside.resolve("original"),
            backupPath = outside.resolve("claude").resolve("skill-1"),
            createdAt = Instant.parse("2026-09-05T10:00:00Z"),
            operationId = "external",
            representation = EffectiveSyncMode.COPY,
        )
        Files.createDirectories(externalBackup.backupPath)
        BackupMetadataStore.write(
            externalBackup,
            SkillInstanceKey("host", SkillScope.GLOBAL, "/shared", "skill-1"),
            "claude",
        )
        assumeTrue(
            WindowsJunctionStrategy().createLink(outside, backupRoot.resolve("escape")) is LinkResult.Success,
            "junction creation is unavailable",
        )

        assertTrue(BackupMetadataStore.listBackups(backupRoot).isEmpty())
    }

    @Test
    fun `an empty or missing backup root yields no records`() {
        assertEquals(emptyList<StoredBackupRecord>(), BackupMetadataStore.listBackups(root.resolve("does-not-exist")))
    }
}
