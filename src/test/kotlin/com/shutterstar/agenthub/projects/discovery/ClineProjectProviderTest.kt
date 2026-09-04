package com.shutterstar.agenthub.projects.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class ClineProjectProviderTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `missing and empty storage return no sessions`() {
        val provider = ClineProjectProvider(dataDirectory())

        assertFalse(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())

        Files.createDirectories(dataDirectory().resolve("sessions"))
        assertTrue(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())
    }

    @Test
    fun `discovers sessions from current database location`() {
        val database = createDatabase("db/sessions.db")
        val project = tempDirectory.resolve("work/project")
        val provider = ClineProjectProvider(dataDirectory()) { selected ->
            assertEquals(database, selected)
            listOf(
                ClineSessionRecord(
                    "cline-one",
                    project.toString(),
                    Instant.parse("2026-08-25T10:00:00Z"),
                    Instant.parse("2026-08-25T11:00:00Z"),
                ),
            )
        }

        val session = provider.discover().single()

        assertEquals("cline", session.agentId)
        assertEquals("cline-one", session.sessionId)
        assertEquals(project.toString(), session.rawProjectPath)
        assertEquals(Instant.parse("2026-08-25T11:00:00Z"), session.updatedAt)
        assertEquals(database.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    @Test
    fun `SQLite reader uses readonly mode and falls back to legacy schema`() {
        val commands = mutableListOf<List<String>>()
        val project = "K:\\Work\tProject"
        val output = listOf(
            hex("cline-id"),
            hex(project),
            hex("2026-08-25T10:00:00Z"),
            hex("2026-08-25T12:00:00Z"),
        ).joinToString("\t")
        val reader = ClineSqliteReader { command, _ ->
            commands += command
            if (commands.size == 1) null else output
        }

        val session = reader.readSessions(tempDirectory.resolve("sessions.db")).single()

        assertEquals(project, session.directory)
        assertEquals(Instant.parse("2026-08-25T10:00:00Z"), session.startedAt)
        assertEquals(Instant.parse("2026-08-25T12:00:00Z"), session.updatedAt)
        assertEquals(2, commands.size)
        assertTrue(commands.all { "-readonly" in it })
        assertTrue(commands.all { it.last().contains("LIMIT 20000") })
        assertTrue(commands.first().last().contains("workspace_root"))
        assertTrue(commands.last().last().contains("hex(cwd)"))
    }

    @Test
    fun `JSON fallback supplies title and ignores malformed sessions`() {
        val project = tempDirectory.resolve("work/json-project")
        writeSession("valid/valid.json", """{
            "session_id":"json-session",
            "cwd":${json(project.toString())},
            "started_at":"2026-08-25T09:00:00Z",
            "updated_at":"2026-08-25T10:00:00Z",
            "metadata":{"title":"JSON title","prompt":"not surfaced"},
            "unknown":{"cwd":"wrong"}
        }""".trimIndent())
        writeSession("broken/broken.json", "{unfinished")
        writeSession("missing/missing.json", """{"session_id":"missing-cwd"}""")

        val session = ClineProjectProvider(dataDirectory()).discover().single()

        assertEquals("json-session", session.sessionId)
        assertEquals(project.toString(), session.rawProjectPath)
        assertEquals("JSON title", session.metadata["title"])
        assertEquals(setOf("title"), session.metadata.keys)
    }

    @Test
    fun `duplicate database and JSON records keep newest activity`() {
        createDatabase("sessions/sessions.db")
        val oldProject = tempDirectory.resolve("work/old")
        val newProject = tempDirectory.resolve("work/new")
        writeSession(
            "duplicate/duplicate.json",
            """{"session_id":"duplicate","cwd":${json(newProject.toString())},"updated_at":"2026-08-26T12:00:00Z"}""",
            Instant.parse("2026-08-26T12:00:00Z"),
        )
        val provider = ClineProjectProvider(dataDirectory()) {
            listOf(
                ClineSessionRecord(
                    "duplicate",
                    oldProject.toString(),
                    null,
                    Instant.parse("2026-08-25T12:00:00Z"),
                ),
            )
        }

        val session = provider.discover().single()

        assertEquals(newProject.toString(), session.rawProjectPath)
        assertEquals(Instant.parse("2026-08-26T12:00:00Z"), session.updatedAt)
    }

    @Test
    fun `database failure is surfaced when no fallback session exists`() {
        createDatabase("db/sessions.db")
        val provider = ClineProjectProvider(dataDirectory()) { throw IOException("fixture failure") }

        assertThrows(IOException::class.java) { provider.discover() }
        val result = ProjectDiscoveryService(providers = listOf(provider)).discover()
        assertEquals("cline", result.warnings.single().agentId)
    }

    private fun dataDirectory(): Path = tempDirectory.resolve("cline-data")

    private fun createDatabase(relativePath: String): Path {
        val file = dataDirectory().resolve(relativePath)
        Files.createDirectories(file.parent)
        return Files.createFile(file)
    }

    private fun writeSession(
        relativePath: String,
        contents: String,
        modifiedAt: Instant = Instant.parse("2026-08-25T10:00:00Z"),
    ): Path {
        val file = dataDirectory().resolve("sessions").resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents)
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt))
        return file
    }

    private fun hex(value: String): String = value.toByteArray(Charsets.UTF_8)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }
}
