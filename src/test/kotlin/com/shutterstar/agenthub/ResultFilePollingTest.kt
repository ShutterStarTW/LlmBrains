package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ResultFilePollingTest {
    @TempDir lateinit var root: Path

    @Test
    fun `should expire a nonempty result without a completion marker`() {
        val file = root.resolve("result")
        Files.writeString(file, "claude=1\n")
        var nanos = 0L
        val polling = ResultFilePolling(file, 1000) { nanos }
        assertEquals(ResultFilePolling.Outcome.Pending, polling.poll())
        nanos = 1_000_000_000L
        assertEquals(ResultFilePolling.Outcome.TimedOut, polling.poll())
    }

    @Test
    fun `should require a whole completion marker line`() {
        val file = root.resolve("result")
        Files.writeString(file, "name=done=1 unfinished\n")
        val polling = ResultFilePolling(file, 1000) { 0L }
        assertEquals(ResultFilePolling.Outcome.Pending, polling.poll())
        val complete = "ok=1\ndone=1\n"
        Files.writeString(file, complete)
        assertEquals(ResultFilePolling.Outcome.Complete(complete), polling.poll())
    }

    @Test
    fun `should expire a missing result as well`() {
        var nanos = 0L
        val polling = ResultFilePolling(root.resolve("missing"), 1000) { nanos }
        assertEquals(ResultFilePolling.Outcome.Pending, polling.poll())
        nanos = 1_000_000_001L
        assertEquals(ResultFilePolling.Outcome.TimedOut, polling.poll())
    }
}
