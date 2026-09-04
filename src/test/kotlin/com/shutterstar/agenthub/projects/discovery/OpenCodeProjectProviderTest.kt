package com.shutterstar.agenthub.projects.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

class OpenCodeProjectProviderTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `missing and empty storage return no sessions`() {
        val provider = OpenCodeProjectProvider(dataDirectory())

        assertFalse(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())

        Files.createDirectories(dataDirectory().resolve("project"))
        assertTrue(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())
    }

    @Test
    fun `discovers sessions from read only database records`() {
        val database = createDatabase("opencode.db")
        val projectPath = tempDirectory.resolve("work/database-project")
        val createdAt = Instant.parse("2026-08-20T10:00:00Z")
        val updatedAt = Instant.parse("2026-08-20T12:00:00Z")
        val provider = OpenCodeProjectProvider(dataDirectory()) { selectedDatabase ->
            assertEquals(database, selectedDatabase)
            listOf(OpenCodeSessionRecord("ses_one", projectPath.toString(), "Database session", createdAt, updatedAt))
        }

        val session = provider.discover().single()

        assertEquals("opencode", session.agentId)
        assertEquals("ses_one", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
        assertEquals(createdAt, session.startedAt)
        assertEquals(updatedAt, session.updatedAt)
        assertEquals("Database session", session.metadata["title"])
        assertEquals(database.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    @Test
    fun `SQLite reader uses readonly arguments and parses hex encoded metadata`() {
        val commands = mutableListOf<List<String>>()
        val projectPath = "K:\\Work\tProject"
        val output = listOf(
            listOf(
                hex("ses_valid"),
                hex(projectPath),
                hex("A title with\ttab"),
                "1787216400000",
                "1787220000000",
            ).joinToString("\t"),
            "malformed-row",
            listOf(hex("ses_invalid_time"), hex(projectPath), "", "invalid", "invalid").joinToString("\t"),
        ).joinToString("\n")
        val reader = OpenCodeSqliteReader { command, _ ->
            commands += command
            output
        }

        val sessions = reader.readSessions(tempDirectory.resolve("database with spaces.db"))

        assertEquals(2, sessions.size)
        assertEquals(projectPath, sessions.first().directory)
        assertEquals("A title with\ttab", sessions.first().title)
        assertEquals(Instant.ofEpochMilli(1787216400000), sessions.first().createdAt)
        assertEquals(null, sessions.last().updatedAt)
        assertTrue("-readonly" in commands.single())
        assertTrue(commands.single().any { it.endsWith("database with spaces.db") })
        assertTrue(commands.single().last().startsWith("SELECT hex(id)"))
        assertTrue(commands.single().last().contains("LIMIT 20000"))
    }

    @Test
    fun `legacy JSON storage supplies project timestamps and title`() {
        val projectPath = tempDirectory.resolve("work/legacy-project")
        val createdAt = Instant.parse("2026-08-21T09:00:00Z")
        val updatedAt = Instant.parse("2026-08-21T11:00:00Z")
        writeLegacySession(
            "project-key",
            "ses_legacy.json",
            legacyJson("ses_legacy", projectPath.toString(), "Legacy title", createdAt, updatedAt),
            modifiedAt = Instant.parse("2026-08-21T10:00:00Z"),
        )

        val session = OpenCodeProjectProvider(dataDirectory()).discover().single()

        assertEquals("ses_legacy", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
        assertEquals(createdAt, session.startedAt)
        assertEquals(updatedAt, session.updatedAt)
        assertEquals("Legacy title", session.metadata["title"])
    }

    @Test
    fun `discovers sessions from the pre SQLite storage directory`() {
        val projectPath = tempDirectory.resolve("work/pre-sqlite-project")
        val directory = Files.createDirectories(dataDirectory().resolve("storage/session/project-key"))
        Files.writeString(
            directory.resolve("ses_pre_sqlite.json"),
            legacyJson("ses_pre_sqlite", projectPath.toString(), "Pre-SQLite", null, null),
        )

        val session = OpenCodeProjectProvider(dataDirectory()).discover().single()

        assertEquals("ses_pre_sqlite", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
        assertEquals("Pre-SQLite", session.metadata["title"])
    }

    @Test
    fun `malformed and missing legacy fields do not abort discovery`() {
        val projectPath = tempDirectory.resolve("work/valid")
        writeLegacySession("key", "broken.json", "{unfinished")
        writeLegacySession("key", "missing-directory.json", """{"id":"missing"}""")
        writeLegacySession(
            "key",
            "fallback-id.json",
            legacyJson(null, projectPath.toString(), null, null, null),
        )

        val sessions = OpenCodeProjectProvider(dataDirectory()).discover()

        assertEquals(1, sessions.size)
        assertEquals("fallback-id", sessions.single().sessionId)
        assertEquals(projectPath.toString(), sessions.single().rawProjectPath)
    }

    @Test
    fun `a large realistic legacy session store is walked on the real filesystem within budget`() {
        val projectCount = 60
        val sessionsPerProject = 50
        repeat(projectCount) { projectIndex ->
            repeat(sessionsPerProject) { sessionIndex ->
                writeLegacySession(
                    "project-$projectIndex",
                    "session-$sessionIndex.json",
                    legacyJson(
                        "p$projectIndex-s$sessionIndex",
                        tempDirectory.resolve("work/project-$projectIndex").toString(),
                        null,
                        null,
                        null,
                    ),
                )
            }
        }

        val sessions = assertTimeout(
            Duration.ofSeconds(45),
            ThrowingSupplier { OpenCodeProjectProvider(dataDirectory()).discover() },
        )

        assertEquals(projectCount * sessionsPerProject, sessions.size)
        assertEquals(projectCount, sessions.mapTo(mutableSetOf()) { it.rawProjectPath }.size)
    }

    @Test
    fun `legacy session scanning is bounded`() {
        val directory = Files.createDirectories(dataDirectory().resolve("project"))
        repeat(3) { index ->
            Files.writeString(
                directory.resolve("$index.json"),
                legacyJson(
                    "session-$index",
                    tempDirectory.resolve("work/$index").toString(),
                    null,
                    null,
                    null,
                ),
            )
        }

        val sessions = OpenCodeProjectProvider(dataDirectory(), maxLegacyScanEntries = 2).discover()

        assertEquals(2, sessions.size)
    }

    @Test
    fun `unknown nested legacy fields do not replace top level metadata`() {
        val projectPath = tempDirectory.resolve("work/real")
        val json = """{"future":{"directory":"wrong","id":"nested"},"id":"real",""" +
            """"directory":${json(projectPath.toString())},"title":"Real",""" +
            """"time":{"created":1787216400000,"updated":1787220000000,"future":{"updated":1}}}"""
        writeLegacySession("key", "unknown.json", json)

        val session = OpenCodeProjectProvider(dataDirectory()).discover().single()

        assertEquals("real", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
        assertEquals("Real", session.metadata["title"])
    }

    @Test
    fun `duplicate records across databases and legacy storage keep newest activity`() {
        createDatabase("opencode.db")
        createDatabase("opencode-preview.db")
        val oldProject = tempDirectory.resolve("work/old")
        val newProject = tempDirectory.resolve("work/new")
        writeLegacySession(
            "key",
            "duplicate.json",
            legacyJson(
                "duplicate",
                oldProject.toString(),
                null,
                Instant.parse("2026-08-20T09:00:00Z"),
                Instant.parse("2026-08-20T10:00:00Z"),
            ),
            modifiedAt = Instant.parse("2026-08-20T10:00:00Z"),
        )
        val provider = OpenCodeProjectProvider(dataDirectory()) { database ->
            if (database.fileName.toString().contains("preview")) {
                listOf(
                    OpenCodeSessionRecord(
                        "duplicate",
                        newProject.toString(),
                        null,
                        Instant.parse("2026-08-22T09:00:00Z"),
                        Instant.parse("2026-08-22T10:00:00Z"),
                    ),
                )
            } else {
                emptyList()
            }
        }

        val session = provider.discover().single()

        assertEquals(newProject.toString(), session.rawProjectPath)
        assertEquals(Instant.parse("2026-08-22T10:00:00Z"), session.updatedAt)
    }

    @Test
    fun `database failure is surfaced for partial discovery warning`() {
        createDatabase("opencode.db")
        val provider = OpenCodeProjectProvider(dataDirectory()) { throw IOException("fixture failure") }

        assertThrows(IOException::class.java) { provider.discover() }
        val result = ProjectDiscoveryService(providers = listOf(provider)).discover()
        assertTrue(result.projects.isEmpty())
        assertEquals("opencode", result.warnings.single().agentId)
    }

    private fun dataDirectory(): Path = tempDirectory.resolve("opencode-data")

    private fun createDatabase(name: String): Path {
        Files.createDirectories(dataDirectory())
        return Files.createFile(dataDirectory().resolve(name))
    }

    private fun writeLegacySession(
        projectKey: String,
        fileName: String,
        contents: String,
        modifiedAt: Instant = Instant.parse("2026-08-20T10:00:00Z"),
    ): Path {
        val directory = Files.createDirectories(
            dataDirectory().resolve("project/$projectKey/storage/session/$projectKey"),
        )
        val file = directory.resolve(fileName)
        Files.writeString(file, contents)
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt))
        return file
    }

    private fun legacyJson(
        id: String?,
        directory: String,
        title: String?,
        createdAt: Instant?,
        updatedAt: Instant?,
    ): String {
        val idField = id?.let { "\"id\":${json(it)}," }.orEmpty()
        val titleField = title?.let { ",\"title\":${json(it)}" }.orEmpty()
        val created = createdAt?.toEpochMilli()?.toString() ?: "null"
        val updated = updatedAt?.toEpochMilli()?.toString() ?: "null"
        return "{$idField\"directory\":${json(directory)}$titleField," +
            "\"time\":{\"created\":$created,\"updated\":$updated},\"unknown\":true}"
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
