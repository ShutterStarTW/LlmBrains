package com.shutterstar.agenthub.environment.skills.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShareChangeTest {
    @Test fun `newly checked agents are shared and previously shared agents that got unchecked are stopped`() {
        val change = ShareChange.of(currentlyShared = setOf("claude", "codex"), selected = setOf("codex", "cursor"))

        assertEquals(setOf("cursor"), change.share)
        assertEquals(setOf("claude"), change.stop)
        assertFalse(change.isEmpty)
    }

    @Test fun `agents left as they were are not touched`() {
        val change = ShareChange.of(currentlyShared = setOf("claude"), selected = setOf("claude"))

        assertTrue(change.share.isEmpty())
        assertTrue(change.stop.isEmpty())
        assertTrue(change.isEmpty)
    }

    @Test fun `nothing shared and nothing chosen is no change`() {
        assertTrue(ShareChange.of(emptySet(), emptySet()).isEmpty)
    }

    @Test fun `unchecking everything that was shared stops all of it and shares nothing`() {
        val change = ShareChange.of(currentlyShared = setOf("claude", "codex"), selected = emptySet())

        assertTrue(change.share.isEmpty())
        assertEquals(setOf("claude", "codex"), change.stop)
    }

    @Test fun `a first share with nothing shared before only shares`() {
        val change = ShareChange.of(currentlyShared = emptySet(), selected = setOf("claude"))

        assertEquals(setOf("claude"), change.share)
        assertTrue(change.stop.isEmpty())
    }
}
