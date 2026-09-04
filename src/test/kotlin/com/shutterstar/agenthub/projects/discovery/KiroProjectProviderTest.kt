package com.shutterstar.agenthub.projects.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class KiroProjectProviderTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `missing and empty storage return no sessions`() {
        val provider = KiroProjectProvider(kiroDirectory())

        assertFalse(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())

        Files.createDirectories(sessionsDirectory())
        assertTrue(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())
    }

    @Test
    fun `discovers Kiro metadata without reading transcript files`() {
        val project = tempDirectory.resolve("work/kiro-project")
        val metadata = writeSession(
            "kiro-one.json",
            """{
                "session_id":"kiro-one",
                "cwd":${json(project.toString())},
                "created_at":"2026-08-25T09:00:00Z",
                "updated_at":"2026-08-25T11:00:00Z",
                "title":"Kiro title",
                "session_state":{"secret":"ignored"}
            }""".trimIndent(),
            Instant.parse("2026-08-25T10:00:00Z"),
        )
        Files.writeString(sessionsDirectory().resolve("kiro-one.jsonl"), "sensitive transcript fixture")

        val session = KiroProjectProvider(kiroDirectory()).discover().single()

        assertEquals("kiro", session.agentId)
        assertEquals("kiro-one", session.sessionId)
        assertEquals(project.toString(), session.rawProjectPath)
        assertEquals(Instant.parse("2026-08-25T09:00:00Z"), session.startedAt)
        assertEquals(Instant.parse("2026-08-25T11:00:00Z"), session.updatedAt)
        assertEquals("Kiro title", session.metadata["title"])
        assertEquals(metadata.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    @Test
    fun `malformed missing and future fields do not abort discovery`() {
        val project = tempDirectory.resolve("work/valid")
        writeSession("broken.json", "{unfinished")
        writeSession("missing-cwd.json", """{"session_id":"missing"}""")
        writeSession(
            "fallback-id.json",
            """{"cwd":${json(project.toString())},"future":{"cwd":"wrong"},"title":"Valid"}""",
        )

        val session = KiroProjectProvider(kiroDirectory()).discover().single()

        assertEquals("fallback-id", session.sessionId)
        assertEquals(project.toString(), session.rawProjectPath)
        assertEquals("Valid", session.metadata["title"])
    }

    @Test
    fun `numeric timestamps are supported and invalid values fall back to file time`() {
        val firstProject = tempDirectory.resolve("work/numeric")
        val secondProject = tempDirectory.resolve("work/invalid")
        writeSession(
            "numeric.json",
            """{"session_id":"numeric","cwd":${json(firstProject.toString())},"created_at":1787652000000,"updated_at":1787655600000}""",
            Instant.parse("2026-08-25T09:00:00Z"),
        )
        val fallbackTime = Instant.parse("2026-08-26T13:00:00Z")
        writeSession(
            "invalid.json",
            """{"session_id":"invalid","cwd":${json(secondProject.toString())},"created_at":"bad","updated_at":"bad"}""",
            fallbackTime,
        )

        val sessions = KiroProjectProvider(kiroDirectory()).discover().associateBy { it.sessionId }

        assertEquals(Instant.ofEpochMilli(1787652000000), sessions.getValue("numeric").startedAt)
        assertEquals(Instant.ofEpochMilli(1787655600000), sessions.getValue("numeric").updatedAt)
        assertNull(sessions.getValue("invalid").startedAt)
        assertEquals(fallbackTime, sessions.getValue("invalid").updatedAt)
    }

    @Test
    fun `duplicate session ids keep newest metadata file`() {
        val oldProject = tempDirectory.resolve("work/old")
        val newProject = tempDirectory.resolve("work/new")
        writeSession(
            "old.json",
            """{"session_id":"duplicate","cwd":${json(oldProject.toString())},"updated_at":"2026-08-24T10:00:00Z"}""",
            Instant.parse("2026-08-24T10:00:00Z"),
        )
        writeSession(
            "new.json",
            """{"session_id":"duplicate","cwd":${json(newProject.toString())},"updated_at":"2026-08-26T10:00:00Z"}""",
            Instant.parse("2026-08-26T10:00:00Z"),
        )

        val session = KiroProjectProvider(kiroDirectory()).discover().single()

        assertEquals(newProject.toString(), session.rawProjectPath)
    }

    private fun kiroDirectory(): Path = tempDirectory.resolve(".kiro")

    private fun sessionsDirectory(): Path = kiroDirectory().resolve("sessions/cli")

    private fun writeSession(fileName: String, contents: String, modifiedAt: Instant = Instant.parse("2026-08-25T10:00:00Z")): Path {
        Files.createDirectories(sessionsDirectory())
        val file = sessionsDirectory().resolve(fileName)
        Files.writeString(file, contents)
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt))
        return file
    }

    private fun json(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
