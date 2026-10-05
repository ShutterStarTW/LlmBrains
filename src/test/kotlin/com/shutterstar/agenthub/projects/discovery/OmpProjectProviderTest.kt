package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OmpProjectProviderTest {
    @TempDir
    lateinit var home: Path

    private fun sessionsDirectory(): Path = home.resolve("agent/sessions")

    private fun provider() = OmpProjectProvider(home.resolve("agent"))

    private fun titleSlot(title: String) = """{"type":"title","v":1,"title":${json(title)},"source":"auto","updatedAt":"2026-10-01T10:00:00Z","pad":"      "}"""

    private fun header(id: String, cwd: String, title: String = "Session title") =
        """{"type":"session","version":3,"id":${json(id)},"timestamp":"2026-10-01T10:00:00.000Z","cwd":${json(cwd)},"title":${json(title)},"titleSource":"auto"}"""

    private fun message(role: String, text: String, attribution: String? = null): String {
        val attr = attribution?.let { ""","attribution":${json(it)}""" }.orEmpty()
        return """{"type":"message","id":"m","parentId":null,"timestamp":"2026-10-01T10:00:01.000Z","message":{"role":${json(role)},"content":[{"type":"text","text":${json(text)}}]$attr}}"""
    }

    private fun write(bucket: String, name: String, vararg lines: String): Path {
        val directory = Files.createDirectories(sessionsDirectory().resolve(bucket))
        return Files.writeString(directory.resolve(name), lines.joinToString("\n", postfix = "\n"))
    }

    @Test
    fun `should read the working directory title and user prompts from a session`() {
        val project = home.resolve("work/app").toString()
        write(
            "--work-app--", "2026-10-01T10-00-00-000Z_abc.jsonl",
            titleSlot("Session title"), header("abc-123", project),
            message("user", "Fix the failing build", "user"),
            message("assistant", "On it"),
            message("user", "Also add a test"),
            message("user", "<injected> agent follow-up", "agent"),
        )

        val provider = provider()
        val session = provider.discover().single()

        assertEquals("omp", session.agentId)
        assertEquals("abc-123", session.sessionId)
        assertEquals(project, session.rawProjectPath)
        assertEquals("Session title", session.metadata["title"])
        assertEquals("2", session.metadata[UserMessageTally.MESSAGE_COUNT_KEY])
        assertEquals("Fix the failing build", session.metadata[UserMessageTally.FIRST_MESSAGE_KEY])
    }

    @Test
    fun `should accept a legacy file that starts with the header and ignore sub directories`() {
        val project = home.resolve("work/legacy").toString()
        write("--work-legacy--", "a.jsonl", header("legacy-1", project), message("user", "Hello"))
        val subagents = Files.createDirectories(sessionsDirectory().resolve("--work-legacy--/a"))
        Files.writeString(subagents.resolve("Child.jsonl"), header("child-1", project) + "\n")

        val sessions = provider().discover()

        assertEquals(listOf("legacy-1"), sessions.map { it.sessionId })
    }

    @Test
    fun `should skip files without a valid header and survive malformed lines`() {
        val project = home.resolve("work/mixed").toString()
        write("--work-mixed--", "good.jsonl", titleSlot("T"), header("good-1", project), "{not json", message("user", "Hi"))
        write("--work-mixed--", "no-header.jsonl", message("user", "No header"))
        write("--work-mixed--", "no-cwd.jsonl", """{"type":"session","id":"no-cwd-1","timestamp":"2026-10-01T10:00:00Z"}""")
        write("--work-mixed--", "notes.txt", "ignored")

        val sessions = provider().discover()

        assertEquals(listOf("good-1"), sessions.map { it.sessionId })
    }

    @Test
    fun `should be unavailable without a sessions directory and empty when it has no sessions`() {
        assertFalse(provider().isAvailable())
        assertTrue(provider().discover().isEmpty())

        Files.createDirectories(sessionsDirectory())

        assertTrue(provider().isAvailable())
        assertTrue(provider().discover().isEmpty())
    }

    @Test
    fun `should keep the newest of two files with the same session id`() {
        val project = home.resolve("work/dup").toString()
        val older = write("--work-dup--", "old.jsonl", header("same-id", project))
        val newer = write("--work-dup--", "new.jsonl", header("same-id", project))
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000))
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(1_800_000_000_000))

        val session = provider().discover().single()

        assertTrue(session.sourcePath!!.endsWith("new.jsonl"))
    }
}
