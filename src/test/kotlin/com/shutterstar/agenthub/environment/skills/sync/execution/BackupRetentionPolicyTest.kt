package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit

class BackupRetentionPolicyTest {
    private val now = Instant.parse("2026-09-05T12:00:00Z")
    private val key = SkillInstanceKey("host", SkillScope.GLOBAL, "/shared", "skill-1")

    @Test
    fun `keeps the last five even when they are older than the max age`() {
        val records = (0 until 5).map { index -> record(daysAgo = 100 + index) }

        assertTrue(BackupRetentionPolicy.recordsToDelete(records, now).isEmpty())
    }

    @Test
    fun `deletes only backups beyond the keep count that are also older than the max age`() {
        val fresh = (0 until 5).map { index -> record(daysAgo = index) }
        val staleBeyondFloor = record(daysAgo = 45)
        val freshBeyondFloor = record(daysAgo = 1)
        val records = fresh + staleBeyondFloor + freshBeyondFloor

        val toDelete = BackupRetentionPolicy.recordsToDelete(records, now)

        assertEquals(listOf(staleBeyondFloor), toDelete)
    }

    @Test
    fun `retention groups are isolated per skill instance and agent`() {
        val otherAgent = record(daysAgo = 60, agentId = "codex")
        val otherSkill = record(daysAgo = 60, key = key.copy(skillId = "skill-2"))
        val records = (0 until 5).map { index -> record(daysAgo = 50 + index) } + otherAgent + otherSkill

        val toDelete = BackupRetentionPolicy.recordsToDelete(records, now)

        assertEquals(emptySet<StoredBackupRecord>(), toDelete.toSet())
    }

    private var sequence = 0

    private fun record(daysAgo: Int, agentId: String = "claude", key: SkillInstanceKey = this.key): StoredBackupRecord {
        val id = sequence++
        return StoredBackupRecord(
            instanceKey = key,
            agentId = agentId,
            backup = SkillBackup(
                id = "op-$id:$agentId",
                originalPath = Path.of("/agent/$agentId/skill"),
                backupPath = Path.of("/backups/op-$id/$agentId/${key.skillId}"),
                createdAt = now.minus(daysAgo.toLong(), ChronoUnit.DAYS),
                operationId = "op-$id",
                representation = EffectiveSyncMode.COPY,
            ),
        )
    }
}
