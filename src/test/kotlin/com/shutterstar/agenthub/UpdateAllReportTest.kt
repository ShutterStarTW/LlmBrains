package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UpdateAllReportTest {
    @Test
    fun `should retain failed updates and remove only successful attempted agents`() {
        val report = UpdateAllReport.parse(
            "ok=1\nuptodate=1\nfailed=1\nupdated_names=Claude Code\nupdated_ids=claude\nuptodate_ids=codex\nfailed_ids=vibe\ndone=1\n",
        )
        assertEquals(1, report.failed)
        assertEquals(listOf("Claude Code"), report.updatedNames)
        assertEquals(listOf("new-agent", "vibe"), report.remainingOutdated(
            setOf("claude", "codex", "vibe", "new-agent"), setOf("claude", "codex", "vibe"),
        ))
    }

    @Test
    fun `should preserve unknown results and ignore unrelated reported successes`() {
        val legacy = UpdateAllReport.parse("ok=1\nuptodate=0\nfailed=1\ndone=1\n")
        assertEquals(listOf("claude", "vibe"), legacy.remainingOutdated(setOf("claude", "vibe"), setOf("claude", "vibe")))
        val contradictory = UpdateAllReport.parse("updated_ids=claude,new-agent\nfailed_ids=claude\n")
        assertEquals(listOf("claude", "new-agent"), contradictory.remainingOutdated(setOf("claude", "new-agent"), setOf("claude")))
    }
}
