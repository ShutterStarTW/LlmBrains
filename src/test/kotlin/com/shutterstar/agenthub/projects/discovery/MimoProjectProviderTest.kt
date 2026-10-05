package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.environment.discovery.MimoHomeSupport
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

class MimoProjectProviderTest {
    @TempDir
    lateinit var tempDirectory: Path

    private val created = Instant.parse("2026-10-01T10:00:00Z")
    private val updated = Instant.parse("2026-10-01T12:00:00Z")

    @Test
    fun `should discover sessions from the MiMo database with the mimo agent id`() {
        val database = Files.createFile(tempDirectory.resolve("mimocode.db"))
        val project = tempDirectory.resolve("work/app").toString()
        val provider = MimoProjectProvider(tempDirectory) { selected ->
            assertEquals(database, selected)
            listOf(OpenCodeSessionRecord("ses_ffe5", project, "First", created, updated, userMessageCount = 3, firstMessage = "Fix the build"))
        }

        val session = provider.discover().single()

        assertEquals("mimo", provider.agentId)
        assertEquals("mimo", session.agentId)
        assertEquals("ses_ffe5", session.sessionId)
        assertEquals(project, session.rawProjectPath)
        assertEquals("First", session.metadata["title"])
        assertEquals("3", session.metadata[UserMessageTally.MESSAGE_COUNT_KEY])
        assertEquals("Fix the build", session.metadata[UserMessageTally.FIRST_MESSAGE_KEY])
        assertEquals(database.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    @Test
    fun `should read channel databases and ignore the other OpenCode family databases`() {
        Files.createFile(tempDirectory.resolve("mimocode.db"))
        Files.createFile(tempDirectory.resolve("mimocode-dev.db"))
        Files.createFile(tempDirectory.resolve("opencode.db"))
        Files.createFile(tempDirectory.resolve("kilo.db"))
        Files.createFile(tempDirectory.resolve("mimocode.db-wal"))
        val read = mutableListOf<String>()

        MimoProjectProvider(tempDirectory) { database ->
            read += database.fileName.toString()
            emptyList()
        }.discover()

        assertEquals(listOf("mimocode-dev.db", "mimocode.db"), read.sorted())
    }

    @Test
    fun `should be unavailable without a database and report an unreadable one`() {
        assertFalse(MimoProjectProvider(tempDirectory) { emptyList() }.isAvailable())

        Files.createFile(tempDirectory.resolve("mimocode.db"))
        val failing = MimoProjectProvider(tempDirectory) { throw IOException("unreadable") }

        assertTrue(failing.isAvailable())
        assertThrows(IOException::class.java) { failing.discover() }
    }

    @Test
    fun `should resolve its directories from the XDG defaults of an isolated home`() {
        assertEquals(tempDirectory.resolve(".local/share/mimocode"), MimoHomeSupport.dataDirectory(tempDirectory))
        assertEquals(tempDirectory.resolve(".config/mimocode"), MimoHomeSupport.configDirectory(tempDirectory))
    }
}
