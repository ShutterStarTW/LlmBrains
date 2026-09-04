package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.resolve.ProjectResolver
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

class CursorProjectProviderTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `missing and empty storage return no sessions`() {
        val provider = CursorProjectProvider(cursorDirectory())

        assertFalse(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())

        Files.createDirectories(chatsDirectory())
        assertTrue(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())
    }

    @Test
    fun `discovers Cursor metadata without reading conversation storage`() {
        val project = tempDirectory.resolve("work/cursor-project")
        val metadata = writeMetadata(
            "workspace-hash/cursor-one/meta.json",
            """{
                "schemaVersion":1,
                "createdAtMs":1787652000000,
                "hasConversation":true,
                "title":"Cursor title",
                "updatedAtMs":1787655600000,
                "cwd":${json(project.toString())},
                "future":{"prompt":"ignored"}
            }""".trimIndent(),
            Instant.parse("2026-08-25T13:00:00Z"),
        )
        Files.writeString(metadata.parent.resolve("prompt_history.json"), "sensitive conversation fixture")
        Files.writeString(metadata.parent.resolve("store.db"), "opaque database fixture")

        val session = CursorProjectProvider(cursorDirectory()).discover().single()

        assertEquals("cursor", session.agentId)
        assertEquals("cursor-one", session.sessionId)
        assertEquals(project.toString(), session.rawProjectPath)
        assertEquals(Instant.ofEpochMilli(1787652000000), session.startedAt)
        assertEquals(Instant.ofEpochMilli(1787655600000), session.updatedAt)
        assertEquals("Cursor title", session.metadata["title"])
        assertEquals(metadata.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    @Test
    fun `discovers multiple sessions and projects`() {
        val firstProject = tempDirectory.resolve("work/first")
        val secondProject = tempDirectory.resolve("work/second")
        writeMetadata("first-hash/one/meta.json", metadata("one", firstProject))
        writeMetadata("first-hash/two/meta.json", metadata("two", firstProject))
        writeMetadata("second-hash/three/meta.json", metadata("three", secondProject))

        val sessions = CursorProjectProvider(cursorDirectory()).discover()

        assertEquals(3, sessions.size)
        assertEquals(2, sessions.count { it.rawProjectPath == firstProject.toString() })
        assertEquals(1, sessions.count { it.rawProjectPath == secondProject.toString() })
    }

    @Test
    fun `malformed missing empty and future fields do not abort discovery`() {
        val project = tempDirectory.resolve("work/valid")
        writeMetadata("broken/broken/meta.json", "{unfinished")
        writeMetadata("missing/missing/meta.json", """{"hasConversation":true}""")
        writeMetadata(
            "empty/empty/meta.json",
            """{"hasConversation":false,"cwd":${json(project.toString())}}""",
        )
        writeMetadata(
            "valid/valid/meta.json",
            """{"future":{"cwd":"wrong","hasConversation":false},"hasConversation":true,"cwd":${json(project.toString())}}""",
        )

        val session = CursorProjectProvider(cursorDirectory()).discover().single()

        assertEquals("valid", session.sessionId)
        assertEquals(project.toString(), session.rawProjectPath)
    }

    @Test
    fun `invalid timestamps fall back to metadata file time and missing project is retained`() {
        val missingProject = tempDirectory.resolve("work/missing")
        val modifiedAt = Instant.parse("2026-08-26T13:00:00Z")
        writeMetadata(
            "workspace/invalid-time/meta.json",
            """{
                "createdAtMs":"bad",
                "updatedAtMs":999999999999999999999999,
                "hasConversation":true,
                "cwd":${json(missingProject.toString())}
            }""".trimIndent(),
            modifiedAt,
        )

        val session = CursorProjectProvider(cursorDirectory()).discover().single()

        assertNull(session.startedAt)
        assertEquals(modifiedAt, session.updatedAt)
        assertFalse(Files.exists(missingProject))
    }

    @Test
    fun `duplicate session ids keep newest metadata`() {
        val oldProject = tempDirectory.resolve("work/old")
        val newProject = tempDirectory.resolve("work/new")
        writeMetadata(
            "old-hash/duplicate/meta.json",
            metadata("old", oldProject, updatedAtMs = 1787565600000),
            Instant.parse("2026-08-24T10:00:00Z"),
        )
        writeMetadata(
            "new-hash/duplicate/meta.json",
            metadata("new", newProject, updatedAtMs = 1787738400000),
            Instant.parse("2026-08-26T10:00:00Z"),
        )

        val session = CursorProjectProvider(cursorDirectory()).discover().single()

        assertEquals(newProject.toString(), session.rawProjectPath)
        assertEquals("new", session.metadata["title"])
    }

    @Test
    fun `scan is bounded and ignores project transcript tree`() {
        val project = tempDirectory.resolve("work/project")
        writeMetadata("first-hash/first/meta.json", metadata("first", project))
        writeMetadata("second-hash/second/meta.json", metadata("second", project))
        val transcript = cursorDirectory()
            .resolve("projects/project/agent-transcripts/subagent/subagent.jsonl")
        Files.createDirectories(transcript.parent)
        Files.writeString(transcript, """{"role":"user","message":{"content":"sensitive"}}""")

        val sessions = CursorProjectProvider(cursorDirectory(), maxScanEntries = 1).discover()

        assertEquals(1, sessions.size)
        assertEquals("first", sessions.single().sessionId)
    }

    @Test
    fun `session payload files do not consume the metadata scan budget`() {
        val project = tempDirectory.resolve("work/project")
        val first = writeMetadata("workspace/first/meta.json", metadata("first", project))
        writeMetadata("workspace/second/meta.json", metadata("second", project))
        repeat(20) { index ->
            Files.writeString(first.parent.resolve("payload-$index.json"), "sensitive fixture")
        }

        val sessions = CursorProjectProvider(cursorDirectory(), maxScanEntries = 5).discover()

        assertEquals(setOf("first", "second"), sessions.mapTo(mutableSetOf()) { it.sessionId })
    }

    @Test
    fun `project discovery service aggregates Cursor sessions`() {
        val projectPath = tempDirectory.resolve("work/cursor-project")
        Files.createDirectories(projectPath)
        writeMetadata("workspace/one/meta.json", metadata("First", projectPath))
        writeMetadata("workspace/two/meta.json", metadata("Second", projectPath))
        val service = ProjectDiscoveryService(
            providers = listOf(CursorProjectProvider(cursorDirectory())),
            projectResolver = ProjectResolver { null },
        )

        val project = service.discoverProjects().single()

        assertEquals(projectPath.toRealPath().toString().replace('\\', '/'), project.path)
        assertEquals("cursor", project.agents.single().agentId)
        assertEquals(2, project.agents.single().sessionCount)
        assertEquals(setOf("First", "Second"), project.agents.single().sessions.mapNotNull { it.title }.toSet())
    }

    private fun cursorDirectory(): Path = tempDirectory.resolve(".cursor")

    private fun chatsDirectory(): Path = cursorDirectory().resolve("chats")

    private fun writeMetadata(
        relativePath: String,
        contents: String,
        modifiedAt: Instant = Instant.parse("2026-08-25T10:00:00Z"),
    ): Path {
        val file = chatsDirectory().resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents)
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt))
        return file
    }

    private fun metadata(
        title: String,
        cwd: Path,
        createdAtMs: Long = 1787652000000,
        updatedAtMs: Long = 1787655600000,
    ): String =
        """{"schemaVersion":1,"createdAtMs":$createdAtMs,"hasConversation":true,"title":${json(title)},"updatedAtMs":$updatedAtMs,"cwd":${json(cwd.toString())}}"""

    private fun json(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
