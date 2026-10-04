package com.shutterstar.agenthub.environment.skills.sync.persistence

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAction
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAuditEntry
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class SkillSyncAuditStateMapperTest {
    @TempDir lateinit var root: Path

    @Test
    fun `round-trip preserves every field`() {
        // Built via .host(), like production code, so the contextPath is already normalized -
        // matching what a freshly computed key looks like after the decode-side normalization.
        val key = SkillInstanceKey.host("skill-1", SkillScope.PROJECT, root.resolve("a/canonical"), root.resolve("a"))
        val entry = SyncAuditEntry(
            operationId = "op-1",
            timestamp = Instant.parse("2026-09-02T10:00:00Z"),
            skillId = "skill-1",
            action = SyncAction.SHARE_EVERYWHERE,
            affectedAgents = setOf("claude", "codex"),
            result = SyncOperationStatus.PARTIAL_SUCCESS,
            instanceKey = key,
        )

        val state = SkillSyncAuditStateMapper.withRecorded(SkillSyncAuditState(), entry)
        val decoded = SkillSyncAuditStateMapper.entriesFor(state, "skill-1")

        assertEquals(listOf(entry), decoded)
        assertEquals(listOf(entry), SkillSyncAuditStateMapper.entriesFor(state, key))
    }

    @Test
    fun `entriesFor(key) still matches after the persisted contextPath comes back denormalized`() {
        // Reproduces what a real IDE restart does on Windows: the persisted contextPath round
        // trips through IntelliJ's $USER_HOME$-style macro collapse/expand, which reconstructs the
        // platform's own (original-case, backslash) rendering rather than the lowercase/forward
        // -slash form SkillInstanceKey.host() produces - this used to make View Sync History (and
        // ownership/backup/journal lookups using the same key) come back empty after every restart.
        org.junit.jupiter.api.Assumptions.assumeTrue(java.io.File.separatorChar == '\\')
        val freshKey = SkillInstanceKey.host("skill-1", SkillScope.PROJECT, root.resolve("a/canonical"), root.resolve("a"))
        val denormalizedContextPath = freshKey.contextPath.replace('/', '\\').uppercase()
        val state = SkillSyncAuditState(
            entries = mutableListOf(
                SkillSyncAuditEntryState(
                    operationId = "op-1",
                    timestampEpochMillis = 1_000L,
                    skillId = "skill-1",
                    runtimeId = freshKey.runtimeId,
                    scope = freshKey.scope.name,
                    contextPath = denormalizedContextPath,
                    action = SyncAction.PROMOTE.name,
                    affectedAgents = mutableListOf("claude"),
                    result = SyncOperationStatus.SUCCESS.name,
                ),
            ),
        )

        assertEquals(1, SkillSyncAuditStateMapper.entriesFor(state, freshKey).size)
    }

    @Test
    fun `withRecorded appends rather than replacing`() {
        val first = entry("skill-1", Instant.parse("2026-09-02T09:00:00Z"))
        val second = entry("skill-1", Instant.parse("2026-09-02T10:00:00Z"))

        var state = SkillSyncAuditStateMapper.withRecorded(SkillSyncAuditState(), first)
        state = SkillSyncAuditStateMapper.withRecorded(state, second)

        assertEquals(2, state.entries.size)
        assertEquals(listOf(second, first), SkillSyncAuditStateMapper.entriesFor(state, "skill-1"))
    }

    @Test
    fun `the MAX_ENTRIES cap drops the oldest entry first`() {
        var state = SkillSyncAuditState()
        val entries = (1..SkillSyncAuditState.MAX_ENTRIES + 1).map {
            entry("skill-1", Instant.EPOCH.plusSeconds(it.toLong()))
        }
        entries.forEach { state = SkillSyncAuditStateMapper.withRecorded(state, it) }

        assertEquals(SkillSyncAuditState.MAX_ENTRIES, state.entries.size)
        val decoded = SkillSyncAuditStateMapper.entriesFor(state, "skill-1")
        assertTrue(decoded.none { it.operationId == entries.first().operationId })
        assertTrue(decoded.any { it.operationId == entries.last().operationId })
    }

    @Test
    fun `entries under a mismatched schema version are ignored`() {
        val state = SkillSyncAuditStateMapper.withRecorded(SkillSyncAuditState(), entry("skill-1", Instant.EPOCH))
            .copy(schemaVersion = 999)

        assertEquals(emptyList<SyncAuditEntry>(), SkillSyncAuditStateMapper.entriesFor(state, "skill-1"))
    }

    private fun entry(skillId: String, timestamp: Instant) = SyncAuditEntry(
        operationId = "op-$timestamp",
        timestamp = timestamp,
        skillId = skillId,
        action = SyncAction.SHARE,
        affectedAgents = setOf("claude"),
        result = SyncOperationStatus.SUCCESS,
    )
}
