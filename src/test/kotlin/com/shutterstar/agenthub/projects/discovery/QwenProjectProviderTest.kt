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

class QwenProjectProviderTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `missing and empty storage return no sessions`() {
        val provider = QwenProjectProvider(qwenDirectory())

        assertFalse(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())

        Files.createDirectories(projectsDirectory())
        assertTrue(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())
    }

    @Test
    fun `discovers saved JSONL session and latest event timestamp`() {
        val project = tempDirectory.resolve("work/qwen-project")
        val file = writeChat(
            "project-one/chats/qwen-one.jsonl",
            listOf(
                event("qwen-one", project.toString(), "2026-08-25T09:00:00Z", "system"),
                event("qwen-one", project.toString(), "2026-08-25T12:00:00Z", "assistant"),
            ),
            Instant.parse("2026-08-25T10:00:00Z"),
        )

        val session = QwenProjectProvider(qwenDirectory()).discover().single()

        assertEquals("qwen", session.agentId)
        assertEquals("qwen-one", session.sessionId)
        assertEquals(project.toString(), session.rawProjectPath)
        assertEquals(Instant.parse("2026-08-25T09:00:00Z"), session.startedAt)
        assertEquals(Instant.parse("2026-08-25T12:00:00Z"), session.updatedAt)
        assertEquals(file.toAbsolutePath().normalize().toString(), session.sourcePath)
        assertTrue(session.metadata.isEmpty())
    }

    @Test
    fun `discovers active runtime metadata with numeric timestamps`() {
        val project = tempDirectory.resolve("work/runtime")
        writeRuntime(
            "project-two/chats/runtime-one.runtime.json",
            """{"session_id":"runtime-one","work_dir":${json(project.toString())},"started_at":1787652000,"updated_at":1787655600,"hostname":"ignored"}""",
            Instant.parse("2026-08-25T09:00:00Z"),
        )

        val session = QwenProjectProvider(qwenDirectory()).discover().single()

        assertEquals("runtime-one", session.sessionId)
        assertEquals(project.toString(), session.rawProjectPath)
        assertEquals(Instant.ofEpochSecond(1787652000), session.startedAt)
        assertEquals(Instant.ofEpochSecond(1787655600), session.updatedAt)
    }

    @Test
    fun `malformed missing and unknown fields do not abort discovery`() {
        val project = tempDirectory.resolve("work/valid")
        writeChat("broken/chats/broken.jsonl", listOf("not-json", "{unfinished"))
        writeRuntime("missing/chats/missing.runtime.json", """{"session_id":"missing"}""")
        writeChat(
            "valid/chats/fallback.jsonl",
            listOf(
                "{broken",
                """{"future":{"cwd":"wrong"},"cwd":${json(project.toString())},"timestamp":"bad"}""",
            ),
            Instant.parse("2026-08-26T13:00:00Z"),
        )

        val session = QwenProjectProvider(qwenDirectory()).discover().single()

        assertEquals("fallback", session.sessionId)
        assertEquals(project.toString(), session.rawProjectPath)
        assertNull(session.startedAt)
        assertEquals(Instant.parse("2026-08-26T13:00:00Z"), session.updatedAt)
    }

    @Test
    fun `runtime and saved duplicate session ids keep newest activity`() {
        val oldProject = tempDirectory.resolve("work/old")
        val newProject = tempDirectory.resolve("work/new")
        writeRuntime(
            "duplicate/chats/same.runtime.json",
            """{"session_id":"same","work_dir":${json(oldProject.toString())},"updated_at":"2026-08-24T10:00:00Z"}""",
            Instant.parse("2026-08-24T10:00:00Z"),
        )
        writeChat(
            "duplicate/chats/same.jsonl",
            listOf(event("same", newProject.toString(), "2026-08-26T10:00:00Z", "system")),
            Instant.parse("2026-08-26T10:00:00Z"),
        )

        val session = QwenProjectProvider(qwenDirectory()).discover().single()

        assertEquals(newProject.toString(), session.rawProjectPath)
        assertTrue(session.sourcePath!!.endsWith("same.jsonl"))
    }

    @Test
    fun `central registry exposes all implemented providers alphabetically`() {
        assertEquals(
            listOf("antigravity", "claude", "cline", "codex", "copilot", "cursor", "grok", "kiro", "opencode", "qwen"),
            AgentProjectProviders.all.map { it.agentId },
        )
    }

    private fun qwenDirectory(): Path = tempDirectory.resolve(".qwen")

    private fun projectsDirectory(): Path = qwenDirectory().resolve("projects")

    private fun writeChat(
        relativePath: String,
        lines: List<String>,
        modifiedAt: Instant = Instant.parse("2026-08-25T10:00:00Z"),
    ): Path = writeFile(relativePath, lines.joinToString("\n", postfix = "\n"), modifiedAt)

    private fun writeRuntime(
        relativePath: String,
        contents: String,
        modifiedAt: Instant = Instant.parse("2026-08-25T10:00:00Z"),
    ): Path =
        writeFile(relativePath, contents, modifiedAt)

    private fun writeFile(relativePath: String, contents: String, modifiedAt: Instant): Path {
        val file = projectsDirectory().resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents)
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt))
        return file
    }

    private fun event(sessionId: String, cwd: String, timestamp: String, type: String): String =
        "{\"sessionId\":${json(sessionId)},\"cwd\":${json(cwd)}," +
            "\"timestamp\":${json(timestamp)},\"type\":${json(type)},\"payload\":{\"ignored\":true}}"

    private fun json(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
