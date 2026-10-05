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
import java.time.Instant

class KiloProjectProviderTest {
    @TempDir
    lateinit var tempDirectory: Path

    private val created = Instant.parse("2026-10-01T10:00:00Z")
    private val updated = Instant.parse("2026-10-01T12:00:00Z")

    @Test
    fun `should discover sessions from the Kilo database with the kilo agent id`() {
        val database = Files.createFile(tempDirectory.resolve("kilo.db"))
        val project = tempDirectory.resolve("work/app").toString()
        val provider = KiloProjectProvider(tempDirectory) { selected ->
            assertEquals(database, selected)
            listOf(OpenCodeSessionRecord("ses_a", project, "First", created, updated, userMessageCount = 3, firstMessage = "Fix the build"))
        }

        val session = provider.discover().single()

        assertEquals("kilo", provider.agentId)
        assertEquals("kilo", session.agentId)
        assertEquals("ses_a", session.sessionId)
        assertEquals(project, session.rawProjectPath)
        assertEquals("First", session.metadata["title"])
        assertEquals("3", session.metadata[UserMessageTally.MESSAGE_COUNT_KEY])
        assertEquals("Fix the build", session.metadata[UserMessageTally.FIRST_MESSAGE_KEY])
        assertEquals(database.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    @Test
    fun `should read channel databases next to the stable one and ignore other files`() {
        Files.createFile(tempDirectory.resolve("kilo.db"))
        Files.createFile(tempDirectory.resolve("kilo-dev.db"))
        Files.createFile(tempDirectory.resolve("opencode.db"))
        Files.createFile(tempDirectory.resolve("kilo.db-wal"))
        val read = mutableListOf<String>()
        val provider = KiloProjectProvider(tempDirectory) { database ->
            read += database.fileName.toString()
            emptyList()
        }

        provider.discover()

        assertEquals(listOf("kilo-dev.db", "kilo.db"), read.sorted())
    }

    @Test
    fun `should not scan the OpenCode style legacy JSON storage`() {
        val legacy = Files.createDirectories(tempDirectory.resolve("storage/session/key"))
        Files.writeString(
            legacy.resolve("ses_legacy.json"),
            """{"id":"ses_legacy","directory":"/work/legacy","time":{"created":1,"updated":2}}""",
        )

        val provider = KiloProjectProvider(tempDirectory) { emptyList() }

        assertFalse(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())
    }

    @Test
    fun `should be unavailable without a database and report an unreadable one`() {
        assertFalse(KiloProjectProvider(tempDirectory) { emptyList() }.isAvailable())

        Files.createFile(tempDirectory.resolve("kilo.db"))
        val failing = KiloProjectProvider(tempDirectory) { throw IOException("unreadable") }

        assertTrue(failing.isAvailable())
        assertThrows(IOException::class.java) { failing.discover() }
    }

    @Test
    fun `should keep OpenCode and Kilo apart`() {
        Files.createFile(tempDirectory.resolve("kilo.db"))

        val opencode = OpenCodeProjectProvider(tempDirectory) { emptyList() }

        assertEquals("opencode", opencode.agentId)
        assertFalse(opencode.isAvailable())
    }
}
