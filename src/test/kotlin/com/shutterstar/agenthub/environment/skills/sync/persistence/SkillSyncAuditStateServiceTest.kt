package com.shutterstar.agenthub.environment.skills.sync.persistence

import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAction
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAuditEntry
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class SkillSyncAuditStateServiceTest {
    @Test
    fun `record then entriesFor returns it`() {
        val service = SkillSyncAuditStateService()
        val entry = entry("skill-1")

        service.record(entry)

        assertEquals(listOf(entry), service.entriesFor("skill-1"))
    }

    @Test
    fun `recentEntries returns newest first across skills`() {
        val service = SkillSyncAuditStateService()
        val older = entry("skill-1", Instant.parse("2026-09-02T09:00:00Z"))
        val newer = entry("skill-2", Instant.parse("2026-09-02T10:00:00Z"))
        service.record(older)
        service.record(newer)

        assertEquals(listOf(newer, older), service.recentEntries(limit = 10))
    }

    @Test
    fun `state loaded from persistence (simulating an IDE restart) serves queries without record ever being called`() {
        val entry = entry("skill-1")
        val persisted = SkillSyncAuditStateMapper.withRecorded(SkillSyncAuditState(), entry)

        val service = SkillSyncAuditStateService()
        service.loadState(persisted)

        assertEquals(listOf(entry), service.entriesFor("skill-1"))
    }

    private fun entry(skillId: String, timestamp: Instant = Instant.parse("2026-09-02T10:00:00Z")) = SyncAuditEntry(
        operationId = "op-1",
        timestamp = timestamp,
        skillId = skillId,
        action = SyncAction.PROMOTE,
        affectedAgents = setOf("claude"),
        result = SyncOperationStatus.SUCCESS,
    )
}
