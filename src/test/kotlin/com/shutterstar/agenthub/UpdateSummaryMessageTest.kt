package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UpdateSummaryMessageTest {
    @Test
    fun `summary names updated agents, falls back to counts and reports an all up to date result`() {
        assertEquals(
            "Updated: Claude Code · 0 up to date · 1 failed",
            DetectionResultsWatcher.updateSummaryMessage(1, 0, 1, listOf("Claude Code")),
        )
        assertEquals("2 updated · 1 up to date · 1 failed", DetectionResultsWatcher.updateSummaryMessage(2, 1, 1, emptyList()))
        assertEquals("All 4 agents up to date", DetectionResultsWatcher.updateSummaryMessage(0, 4, 0, emptyList()))
    }
}
