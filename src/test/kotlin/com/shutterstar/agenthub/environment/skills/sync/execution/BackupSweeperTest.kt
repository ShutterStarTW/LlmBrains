package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit

class BackupSweeperTest {
    @TempDir
    lateinit var root: Path

    private val service = BackupService()
    private val key = SkillInstanceKey("host", SkillScope.GLOBAL, "/shared", "skill-1")

    @Test
    fun `sweep removes a stale backup beyond the keep floor and its sidecar, keeps the rest`() {
        val backupRoot = root.resolve("backups")
        val original = Files.createDirectory(root.resolve("original"))
        Files.writeString(original.resolve("SKILL.md"), "content")

        val kept = (0 until 5).map { index -> service.backup("claude", "skill-1", original, backupRoot, "keep-$index", instanceKey = key)!! }
        val stale = service.backup("claude", "skill-1", original, backupRoot, "stale", instanceKey = key)!!
        touchCreatedAt(stale.backupPath, Instant.now().minus(45, ChronoUnit.DAYS))

        val deletedCount = BackupSweeper.sweep(backupRoot)

        assertEquals(1, deletedCount)
        assertFalse(Files.exists(stale.backupPath))
        assertFalse(Files.exists(BackupMetadataStore.sidecarPathFor(stale.backupPath)))
        kept.forEach { assertTrue(Files.exists(it.backupPath), "${it.backupPath} should still exist") }
    }

    /** The sweep reads `createdAt` from the sidecar, not filesystem mtime, so age it directly there. */
    private fun touchCreatedAt(backupPath: Path, createdAt: Instant) {
        val sidecar = BackupMetadataStore.sidecarPathFor(backupPath)
        val properties = java.util.Properties()
        Files.newBufferedReader(sidecar).use { properties.load(it) }
        properties.setProperty("createdAtEpochMilli", createdAt.toEpochMilli().toString())
        Files.newBufferedWriter(sidecar).use { properties.store(it, null) }
    }
}
