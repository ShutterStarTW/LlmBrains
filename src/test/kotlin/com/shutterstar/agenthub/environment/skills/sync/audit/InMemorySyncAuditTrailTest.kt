package com.shutterstar.agenthub.environment.skills.sync.audit

import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemorySyncAuditTrailTest {
    private val trail = InMemorySyncAuditTrail()

    @Test
    fun `record then entriesFor returns it`() {
        val entry = entry(skillId = "skill-1", timestamp = Instant.parse("2026-09-02T10:00:00Z"))

        trail.record(entry)

        assertEquals(listOf(entry), trail.entriesFor("skill-1"))
    }

    @Test
    fun `entries come back newest first`() {
        val older = entry(skillId = "skill-1", timestamp = Instant.parse("2026-09-02T09:00:00Z"))
        val newer = entry(skillId = "skill-1", timestamp = Instant.parse("2026-09-02T10:00:00Z"))
        trail.record(older)
        trail.record(newer)

        assertEquals(listOf(newer, older), trail.entriesFor("skill-1"))
    }

    @Test
    fun `entriesFor filters by skillId`() {
        trail.record(entry(skillId = "skill-1", timestamp = Instant.EPOCH))
        val other = entry(skillId = "skill-2", timestamp = Instant.EPOCH)
        trail.record(other)

        assertEquals(listOf(other), trail.entriesFor("skill-2"))
    }

    @Test
    fun `recentEntries truncates to the limit, newest first, across all skills`() {
        trail.record(entry(skillId = "skill-1", timestamp = Instant.parse("2026-09-02T09:00:00Z")))
        val newest = entry(skillId = "skill-2", timestamp = Instant.parse("2026-09-02T10:00:00Z"))
        trail.record(newest)

        assertEquals(listOf(newest), trail.recentEntries(limit = 1))
    }

    private fun entry(skillId: String, timestamp: Instant) = SyncAuditEntry(
        operationId = "op-1",
        timestamp = timestamp,
        skillId = skillId,
        action = SyncAction.SHARE,
        affectedAgents = setOf("claude"),
        result = SyncOperationStatus.SUCCESS,
    )
}
