package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.ScanBudget
import com.shutterstar.agenthub.projects.model.RawAgentProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class LocalSessionSupportTest {
    @TempDir
    lateinit var tempDirectory: Path

    private fun session(
        id: String = "s1",
        updatedAt: Instant? = null,
        startedAt: Instant? = null,
        sourcePath: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) = RawAgentProject("claude", "/project", id, startedAt, updatedAt, sourcePath, metadata)

    @Test
    fun `deduplicate keeps the most recently active record per session id`() {
        val older = session(updatedAt = Instant.parse("2026-01-01T00:00:00Z"), sourcePath = "a")
        val newer = session(updatedAt = Instant.parse("2026-02-01T00:00:00Z"), sourcePath = "b")

        assertEquals("b", LocalSessionSupport.deduplicate(listOf(older, newer)).single().sourcePath)
        assertEquals("b", LocalSessionSupport.deduplicate(listOf(newer, older)).single().sourcePath)
    }

    @Test
    fun `deduplicate falls back to startedAt when updatedAt is missing`() {
        val started = session(startedAt = Instant.parse("2026-03-01T00:00:00Z"), sourcePath = "started")
        val updated = session(updatedAt = Instant.parse("2026-02-01T00:00:00Z"), sourcePath = "updated")

        assertEquals("started", LocalSessionSupport.deduplicate(listOf(updated, started)).single().sourcePath)
    }

    @Test
    fun `deduplicate breaks an activity tie by the greater source path in either order`() {
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val low = session(updatedAt = at, sourcePath = "/a/session.jsonl")
        val high = session(updatedAt = at, sourcePath = "/b/session.jsonl")

        assertEquals("/b/session.jsonl", LocalSessionSupport.deduplicate(listOf(low, high)).single().sourcePath)
        assertEquals("/b/session.jsonl", LocalSessionSupport.deduplicate(listOf(high, low)).single().sourcePath)
    }

    @Test
    fun `deduplicate merges metadata and the winner's values take precedence`() {
        val older = session(
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            metadata = mapOf("firstMessage" to "from older", "title" to "old title"),
        )
        val newer = session(
            updatedAt = Instant.parse("2026-02-01T00:00:00Z"),
            metadata = mapOf("title" to "new title"),
        )

        val merged = LocalSessionSupport.deduplicate(listOf(older, newer)).single().metadata
        assertEquals("new title", merged["title"])
        assertEquals("from older", merged["firstMessage"])

        // The older record arriving second must still contribute what the winner lacks.
        val reversed = LocalSessionSupport.deduplicate(listOf(newer, older)).single().metadata
        assertEquals("new title", reversed["title"])
        assertEquals("from older", reversed["firstMessage"])
    }

    @Test
    fun `deduplicate keeps different sessions and orders them by recency`() {
        val first = session(id = "one", updatedAt = Instant.parse("2026-01-01T00:00:00Z"))
        val second = session(id = "two", updatedAt = Instant.parse("2026-02-01T00:00:00Z"))

        assertEquals(listOf("two", "one"), LocalSessionSupport.deduplicate(listOf(first, second)).map { it.sessionId })
    }

    @Test
    fun `parseTimestamp understands instants, offsets and epoch values`() {
        assertEquals(Instant.parse("2026-01-01T10:00:00Z"), LocalSessionSupport.parseTimestamp("2026-01-01T10:00:00Z"))
        assertEquals(Instant.parse("2026-01-01T08:00:00Z"), LocalSessionSupport.parseTimestamp("2026-01-01T10:00:00+02:00"))
        assertEquals(Instant.ofEpochSecond(1_787_652_000), LocalSessionSupport.parseTimestamp("1787652000"))
        assertEquals(Instant.ofEpochMilli(1_787_652_000_000), LocalSessionSupport.parseTimestamp("1787652000000"))
        assertNull(LocalSessionSupport.parseTimestamp("not a time"))
        assertNull(LocalSessionSupport.parseTimestamp(null))
        assertNull(LocalSessionSupport.parseTimestamp("  "))
    }

    @Test
    fun `lastTimestamp returns the newest parsable line from the tail`() {
        val file = tempDirectory.resolve("events.jsonl")
        Files.writeString(
            file,
            listOf(
                """{"timestamp":"2026-01-01T00:00:00Z"}""",
                """{"timestamp":"2026-01-02T00:00:00Z"}""",
                """{"note":"no timestamp here"}""",
            ).joinToString("\n"),
        )

        val result = LocalSessionSupport.lastTimestamp(file, 4096, 10) { line ->
            MetadataJsonParser.topLevelStringFields(line, setOf("timestamp"))["timestamp"]
                ?.let(LocalSessionSupport::parseTimestamp)
        }

        assertEquals(Instant.parse("2026-01-02T00:00:00Z"), result)
    }

    @Test
    fun `lastTimestamp is null when no line yields one`() {
        val file = tempDirectory.resolve("empty.jsonl")
        Files.writeString(file, "{\"a\":1}\n{\"b\":2}\n")

        assertNull(LocalSessionSupport.lastTimestamp(file, 4096, 10) { null })
    }

    @Test
    fun `listDirectories returns sorted directories only and tolerates a missing root`() {
        Files.createDirectories(tempDirectory.resolve("b"))
        Files.createDirectories(tempDirectory.resolve("a"))
        Files.writeString(tempDirectory.resolve("file.txt"), "x")

        assertEquals(
            listOf("a", "b"),
            LocalSessionSupport.listDirectories(tempDirectory, 100).map { it.fileName.toString() },
        )
        assertTrue(LocalSessionSupport.listDirectories(tempDirectory.resolve("missing"), 100).isEmpty())
        assertTrue(LocalSessionSupport.listDirectories(tempDirectory.resolve("file.txt"), 100).isEmpty())
    }

    @Test
    fun `listDirectories charges the budget for what it returns and stops when it is spent`() {
        listOf("a", "b", "c").forEach { Files.createDirectories(tempDirectory.resolve(it)) }
        val budget = ScanBudget(2)

        val first = LocalSessionSupport.listDirectories(tempDirectory, budget)

        assertEquals(2, first.size)
        assertFalse(budget.hasRemaining())
        assertTrue(LocalSessionSupport.listDirectories(tempDirectory, budget).isEmpty())
    }
}
