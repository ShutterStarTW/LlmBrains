package com.shutterstar.agenthub.projects.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

class ClaudeProjectProviderTest {
    @TempDir
    lateinit var homeDirectory: Path

    @Test
    fun `missing and empty storage return no sessions`() {
        val provider = ClaudeProjectProvider(homeDirectory)

        assertFalse(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())

        Files.createDirectories(projectsDirectory())
        assertTrue(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())
    }

    @Test
    fun `discovers one valid session from top level metadata`() {
        val projectPath = homeDirectory.resolve("work/project with spaces")
        val startedAt = Instant.parse("2026-08-20T10:15:30Z")
        val modifiedAt = Instant.parse("2026-08-20T11:00:00Z")
        val file = writeSession(
            projectKey = "encoded-project",
            fileName = "fallback-id.jsonl",
            lines = listOf(
                """{"type":"mode","sessionId":"session-one"}""",
                metadataLine("session-one", projectPath.toString(), startedAt.toString()),
            ),
            modifiedAt = modifiedAt,
        )

        val session = ClaudeProjectProvider(homeDirectory).discover().single()

        assertEquals("claude", session.agentId)
        assertEquals("session-one", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
        assertEquals(startedAt, session.startedAt)
        assertEquals(modifiedAt, session.updatedAt)
        assertEquals(file.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    @Test
    fun `discovers multiple sessions and projects`() {
        val firstProject = homeDirectory.resolve("work/first")
        val secondProject = homeDirectory.resolve("work/second")
        writeSession("first-key", "one.jsonl", listOf(metadataLine("one", firstProject.toString())))
        writeSession("first-key", "two.jsonl", listOf(metadataLine("two", firstProject.toString())))
        writeSession("second-key", "three.jsonl", listOf(metadataLine("three", secondProject.toString())))

        val sessions = ClaudeProjectProvider(homeDirectory).discover()

        assertEquals(3, sessions.size)
        assertEquals(2, sessions.count { it.rawProjectPath == firstProject.toString() })
        assertEquals(1, sessions.count { it.rawProjectPath == secondProject.toString() })
    }

    @Test
    fun `malformed records do not abort discovery`() {
        val projectPath = homeDirectory.resolve("work/valid")
        writeSession("project", "malformed.jsonl", listOf("not-json", "{unfinished"))
        writeSession(
            "project",
            "recoverable.jsonl",
            listOf("{broken", metadataLine("recovered", projectPath.toString())),
        )

        val sessions = ClaudeProjectProvider(homeDirectory).discover()

        assertEquals(listOf("recovered"), sessions.map { it.sessionId })
    }

    @Test
    fun `missing session id falls back to file name while missing cwd is skipped`() {
        val projectPath = homeDirectory.resolve("work/fallback")
        writeSession("project", "file-session-id.jsonl", listOf(metadataLine(null, projectPath.toString())))
        writeSession("project", "missing-cwd.jsonl", listOf("""{"sessionId":"no-project"}"""))

        val sessions = ClaudeProjectProvider(homeDirectory).discover()

        assertEquals(1, sessions.size)
        assertEquals("file-session-id", sessions.single().sessionId)
        assertEquals(projectPath.toString(), sessions.single().rawProjectPath)
    }

    @Test
    fun `unknown and nested fields cannot replace top level metadata`() {
        val projectPath = homeDirectory.resolve("work/real")
        val line = """{"type":"user","message":{"cwd":"C:\\wrong","sessionId":"nested"},""" +
            """"future":[{"timestamp":"bad"}],"cwd":${json(projectPath.toString())},""" +
            """"sessionId":"real","timestamp":"2026-08-21T12:00:00Z","newField":true}"""
        writeSession("project", "unknown-fields.jsonl", listOf(line))

        val session = ClaudeProjectProvider(homeDirectory).discover().single()

        assertEquals("real", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
        assertEquals(Instant.parse("2026-08-21T12:00:00Z"), session.startedAt)
    }

    @Test
    fun `invalid timestamp is ignored and file modification time remains available`() {
        val modifiedAt = Instant.parse("2026-08-22T13:00:00Z")
        writeSession(
            "project",
            "invalid-time.jsonl",
            listOf(metadataLine("invalid-time", homeDirectory.resolve("missing-project").toString(), "not-a-time")),
            modifiedAt,
        )

        val session = ClaudeProjectProvider(homeDirectory).discover().single()

        assertNull(session.startedAt)
        assertEquals(modifiedAt, session.updatedAt)
        assertFalse(Files.exists(Path.of(session.rawProjectPath!!)))
    }

    @Test
    fun `duplicate session ids keep only the newest source`() {
        val oldProject = homeDirectory.resolve("work/old")
        val newProject = homeDirectory.resolve("work/new")
        writeSession(
            "first-key",
            "old-copy.jsonl",
            listOf(metadataLine("duplicate", oldProject.toString())),
            Instant.parse("2026-08-20T10:00:00Z"),
        )
        val newest = writeSession(
            "second-key",
            "new-copy.jsonl",
            listOf(metadataLine("duplicate", newProject.toString())),
            Instant.parse("2026-08-23T10:00:00Z"),
        )

        val session = ClaudeProjectProvider(homeDirectory).discover().single()

        assertEquals("duplicate", session.sessionId)
        assertEquals(newProject.toString(), session.rawProjectPath)
        assertEquals(newest.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    @Test
    fun `nested subagent transcripts are not treated as sessions`() {
        val projectPath = homeDirectory.resolve("work/project")
        val sessionFile = writeSession(
            "project-key",
            "parent.jsonl",
            listOf(metadataLine("parent", projectPath.toString())),
        )
        val subagents = Files.createDirectories(sessionFile.parent.resolve("parent/subagents"))
        Files.writeString(
            subagents.resolve("agent-child.jsonl"),
            metadataLine("child", projectPath.toString()),
        )

        val sessions = ClaudeProjectProvider(homeDirectory).discover()

        assertEquals(listOf("parent"), sessions.map { it.sessionId })
    }

    @Test
    fun `oversized metadata line is skipped within the bounded header`() {
        val projectPath = homeDirectory.resolve("work/bounded")
        val oversized = """{"unknown":"${"x".repeat(70_000)}"}"""
        writeSession(
            "project-key",
            "bounded.jsonl",
            listOf(oversized, metadataLine("bounded", projectPath.toString())),
        )

        val session = ClaudeProjectProvider(homeDirectory).discover().single()

        assertEquals("bounded", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
    }

    @Test
    fun `a large realistic session store is walked on the real filesystem within budget`() {
        val projectCount = 60
        val sessionsPerProject = 50
        repeat(projectCount) { projectIndex ->
            repeat(sessionsPerProject) { sessionIndex ->
                writeSession(
                    projectKey = "project-$projectIndex",
                    fileName = "session-$sessionIndex.jsonl",
                    lines = listOf(
                        metadataLine(
                            "p$projectIndex-s$sessionIndex",
                            homeDirectory.resolve("work/project-$projectIndex").toString(),
                        ),
                    ),
                )
            }
        }

        val sessions = assertTimeout(
            Duration.ofSeconds(45),
            ThrowingSupplier { ClaudeProjectProvider(homeDirectory).discover() },
        )

        assertEquals(projectCount * sessionsPerProject, sessions.size)
        assertEquals(projectCount, sessions.mapTo(mutableSetOf()) { it.rawProjectPath }.size)
    }

    @Test
    fun `session directory scanning is bounded`() {
        repeat(3) { index ->
            writeSession(
                "project-key",
                "$index.jsonl",
                listOf(metadataLine("session-$index", homeDirectory.resolve("work/$index").toString())),
            )
        }

        val sessions = ClaudeProjectProvider(homeDirectory, maxSessionEntries = 2).discover()

        assertEquals(2, sessions.size)
    }

    @Test
    private fun projectsDirectory(): Path = homeDirectory.resolve(".claude/projects")

    private fun writeSession(
        projectKey: String,
        fileName: String,
        lines: List<String>,
        modifiedAt: Instant = Instant.parse("2026-08-20T11:00:00Z"),
    ): Path {
        val directory = Files.createDirectories(projectsDirectory().resolve(projectKey))
        val file = directory.resolve(fileName)
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt))
        return file
    }

    private fun metadataLine(
        sessionId: String?,
        cwd: String,
        timestamp: String = "2026-08-20T10:15:30Z",
    ): String {
        val session = sessionId?.let { "\"sessionId\":${json(it)}," }.orEmpty()
        return "{$session\"cwd\":${json(cwd)},\"timestamp\":${json(timestamp)},\"type\":\"user\"}"
    }

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
