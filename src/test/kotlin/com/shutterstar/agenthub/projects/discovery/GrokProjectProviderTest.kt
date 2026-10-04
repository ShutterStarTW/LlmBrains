package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class GrokProjectProviderTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `discovers summary metadata without reading transcripts`() {
        val project = tempDirectory.resolve("work/grok-project")
        val summary = writeSummary(
            project,
            "session-one",
            """
            {
              "info": {"id":"session-one","cwd":${json(project.toString())}},
              "created_at":"2026-09-01T10:00:00Z",
              "updated_at":"2026-09-01T11:00:00Z",
              "last_active_at":"2026-09-01T12:00:00Z",
              "generated_title":"Grok title",
              "session_summary":"sensitive conversation fixture",
              "future":{"ignored":true}
            }
            """.trimIndent(),
            Instant.parse("2026-09-01T09:00:00Z"),
        )
        Files.writeString(summary.parent.resolve("chat_history.jsonl"), "sensitive transcript fixture")

        val session = GrokProjectProvider(grokDirectory()).discover().single()

        assertEquals("grok", session.agentId)
        assertEquals("session-one", session.sessionId)
        assertEquals(project.toString(), session.rawProjectPath)
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), session.startedAt)
        assertEquals(Instant.parse("2026-09-01T12:00:00Z"), session.updatedAt)
        assertEquals("Grok title", session.metadata["title"])
        assertEquals(summary.toAbsolutePath().normalize().toString(), session.sourcePath)
        assertFalse(session.toString().contains("sensitive"))
    }

    @Test
    fun `malformed missing empty and future fields do not abort discovery`() {
        val project = tempDirectory.resolve("work/valid")
        val invalidWorkspace = sessionsDirectory().resolve("%GG")
        val broken = invalidWorkspace.resolve("broken").resolve("summary.json")
        Files.createDirectories(broken.parent)
        Files.writeString(broken, "{unfinished")
        writeSummary(tempDirectory.resolve("missing-cwd"), "missing", """{"info":{"id":"missing"},"future":true}""")
        writeSummary(
            project,
            "valid",
            """
            {
              "info": {"id":"valid","cwd":${json(project.toString())},"extra":"ok"},
              "created_at":"not-a-timestamp",
              "generated_title":"Recovered"
            }
            """.trimIndent(),
            Instant.parse("2026-09-02T08:00:00Z"),
        )

        val sessions = GrokProjectProvider(grokDirectory()).discover().associateBy { it.sessionId }

        assertEquals(setOf("missing", "valid"), sessions.keys)
        assertEquals(project.toString(), sessions.getValue("valid").rawProjectPath)
        assertNull(sessions.getValue("valid").startedAt)
        assertEquals(Instant.parse("2026-09-02T08:00:00Z"), sessions.getValue("valid").updatedAt)
        assertEquals("Recovered", sessions.getValue("valid").metadata["title"])
        assertEquals(tempDirectory.resolve("missing-cwd").toString(), sessions.getValue("missing").rawProjectPath)
    }

    @Test
    fun `falls back to encoded workspace directory when summary is missing`() {
        val project = tempDirectory.resolve("work/encoded")
        val sessionDirectory = Files.createDirectories(
            sessionsDirectory().resolve(percentEncode(project.toString())).resolve("folder-id"),
        )
        Files.setLastModifiedTime(sessionDirectory, FileTime.from(Instant.parse("2026-09-03T09:00:00Z")))

        val session = GrokProjectProvider(grokDirectory()).discover().single()

        assertEquals("folder-id", session.sessionId)
        assertEquals(project.toString(), session.rawProjectPath)
        assertEquals(Instant.parse("2026-09-03T09:00:00Z"), session.updatedAt)
    }

    private fun grokDirectory(): Path = tempDirectory.resolve(".grok")

    private fun sessionsDirectory(): Path = grokDirectory().resolve("sessions")

    private fun writeSummary(
        project: Path,
        sessionId: String,
        contents: String,
        modifiedAt: Instant = Instant.parse("2026-09-01T10:00:00Z"),
    ): Path {
        val file = sessionsDirectory()
            .resolve(percentEncode(project.toString()))
            .resolve(sessionId)
            .resolve("summary.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, contents)
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt))
        return file
    }

    private fun summary(sessionId: String, project: Path, timestamp: String): String =
        """{"info":{"id":${json(sessionId)},"cwd":${json(project.toString())}},"created_at":${json(timestamp)},"last_active_at":${json(timestamp)}}"""

    private fun percentEncode(value: String): String {
        val builder = StringBuilder()
        value.toByteArray(StandardCharsets.UTF_8).forEach { raw ->
            val unsigned = raw.toInt() and 0xFF
            val character = unsigned.toChar()
            if (character.isLetterOrDigit() || character == '.' || character == '-' || character == '_') {
                builder.append(character)
            } else {
                builder.append('%')
                builder.append(unsigned.toString(16).uppercase().padStart(2, '0'))
            }
        }
        return builder.toString()
    }
}
