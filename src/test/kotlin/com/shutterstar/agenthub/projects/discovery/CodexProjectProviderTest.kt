package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.json
import com.shutterstar.agenthub.projects.resolve.ProjectResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

class CodexProjectProviderTest {
    @TempDir
    lateinit var homeDirectory: Path

    @Test
    fun `discovers session metadata from dated rollout tree`() {
        val projectPath = Files.createDirectories(homeDirectory.resolve("work/project with spaces"))
        val startedAt = Instant.parse("2026-08-25T10:00:00Z")
        val updatedAt = Instant.parse("2026-08-25T12:30:00Z")
        val file = writeRollout(
            "sessions/2026/08/25/rollout-session.jsonl",
            listOf(
                sessionMetadata("session-one", projectPath.toString(), startedAt.toString()),
                event(updatedAt.toString()),
            ),
            modifiedAt = Instant.parse("2026-08-25T10:01:00Z"),
        )

        val session = CodexProjectProvider(codexDirectory()).discover().single()

        assertEquals("codex", session.agentId)
        assertEquals("session-one", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
        assertEquals(startedAt, session.startedAt)
        assertEquals(updatedAt, session.updatedAt)
        assertEquals(file.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    @Test
    fun `malformed and incomplete rollouts do not abort discovery`() {
        val projectPath = homeDirectory.resolve("work/valid")
        writeRollout("sessions/2026/08/20/broken.jsonl", listOf("not-json", "{unfinished"))
        writeRollout(
            "sessions/2026/08/20/recoverable.jsonl",
            listOf("{broken", sessionMetadata("recovered", projectPath.toString())),
        )

        val sessions = CodexProjectProvider(codexDirectory()).discover()

        assertEquals(listOf("recovered"), sessions.map { it.sessionId })
    }

    @Test
    fun `missing id falls back to rollout UUID while missing cwd is skipped`() {
        val projectPath = homeDirectory.resolve("work/fallback")
        val uuid = "01a0569c-6006-7de2-9e3c-6ad4ce7e970d"
        writeRollout(
            "sessions/2026/08/20/rollout-2026-08-20T10-00-00-$uuid.jsonl",
            listOf(sessionMetadata(null, projectPath.toString())),
        )
        writeRollout(
            "sessions/2026/08/20/missing-cwd.jsonl",
            listOf("""{"type":"session_meta","payload":{"id":"no-project"}}"""),
        )

        val sessions = CodexProjectProvider(codexDirectory()).discover()

        assertEquals(1, sessions.size)
        assertEquals(uuid, sessions.single().sessionId)
    }

    @Test
    fun `unknown nested fields cannot replace payload metadata`() {
        val projectPath = homeDirectory.resolve("work/real")
        val line = """{"timestamp":"2026-08-20T10:00:00Z","type":"session_meta",""" +
            """"future":{"cwd":"C:\\wrong"},"payload":{"future":[{"id":"nested"}],"id":"real",""" +
            """"cwd":${json(projectPath.toString())},"timestamp":"2026-08-20T09:59:00Z",""" +
            """"base_instructions":{"nested":[1,2,3]}}}"""
        writeRollout("sessions/2026/08/20/unknown.jsonl", listOf(line))

        val session = CodexProjectProvider(codexDirectory()).discover().single()

        assertEquals("real", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
        assertEquals(Instant.parse("2026-08-20T09:59:00Z"), session.startedAt)
    }

    @Test
    fun `invalid timestamps fall back to file modification time`() {
        val modifiedAt = Instant.parse("2026-08-22T13:00:00Z")
        val missingProject = homeDirectory.resolve("work/not-created")
        writeRollout(
            "sessions/2026/08/22/invalid-time.jsonl",
            listOf(sessionMetadata("invalid-time", missingProject.toString(), "not-a-time"), event("also-invalid")),
            modifiedAt,
        )

        val session = CodexProjectProvider(codexDirectory()).discover().single()

        assertNull(session.startedAt)
        assertEquals(modifiedAt, session.updatedAt)
        assertFalse(Files.exists(Path.of(session.rawProjectPath!!)))
    }

    @Test
    fun `active and archived duplicate session ids keep the newest rollout`() {
        val oldProject = homeDirectory.resolve("work/old")
        val newProject = homeDirectory.resolve("work/new")
        writeRollout(
            "sessions/2026/08/20/old.jsonl",
            listOf(sessionMetadata("duplicate", oldProject.toString()), event("2026-08-20T11:00:00Z")),
        )
        val newest = writeRollout(
            "archived_sessions/new.jsonl",
            listOf(sessionMetadata("duplicate", newProject.toString()), event("2026-08-23T11:00:00Z")),
        )

        val session = CodexProjectProvider(codexDirectory()).discover().single()

        assertEquals(newProject.toString(), session.rawProjectPath)
        assertEquals(newest.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    @Test
    fun `a large realistic dated rollout tree is walked on the real filesystem within budget`() {
        val days = 30
        val sessionsPerDay = 100
        repeat(days) { dayIndex ->
            val day = (dayIndex % 28) + 1
            repeat(sessionsPerDay) { sessionIndex ->
                writeRollout(
                    "sessions/2026/08/${day.toString().padStart(2, '0')}/session-$dayIndex-$sessionIndex.jsonl",
                    listOf(
                        sessionMetadata(
                            "d$dayIndex-s$sessionIndex",
                            homeDirectory.resolve("work/project-$dayIndex").toString(),
                        ),
                    ),
                )
            }
        }

        val sessions = assertTimeout(
            Duration.ofSeconds(45),
            ThrowingSupplier { CodexProjectProvider(codexDirectory()).discover() },
        )

        assertEquals(days * sessionsPerDay, sessions.size)
    }

    @Test
    fun `session tree scanning is bounded`() {
        repeat(3) { index ->
            writeRollout(
                "sessions/$index.jsonl",
                listOf(sessionMetadata("session-$index", homeDirectory.resolve("work/$index").toString())),
            )
        }

        val sessions = CodexProjectProvider(codexDirectory(), maxScanEntries = 2).discover()

        assertEquals(2, sessions.size)
    }

    @Test
    fun `Claude and Codex histories for one path aggregate into one project`() {
        val projectPath = Files.createDirectories(homeDirectory.resolve("work/shared-project"))
        val claudeDirectory = Files.createDirectories(homeDirectory.resolve(".claude/projects/shared"))
        Files.writeString(
            claudeDirectory.resolve("claude-session.jsonl"),
            """{"sessionId":"claude-session","cwd":${json(projectPath.toString())},"timestamp":"2026-08-20T10:00:00Z"}""",
        )
        writeRollout(
            "sessions/2026/08/21/codex-session.jsonl",
            listOf(sessionMetadata("codex-session", projectPath.toString(), "2026-08-21T10:00:00Z")),
        )
        val service = ProjectDiscoveryService(
            providers = listOf(
                ClaudeProjectProvider(homeDirectory),
                CodexProjectProvider(codexDirectory()),
            ),
            projectResolver = ProjectResolver { null },
        )

        val project = service.discover().projects.single()

        assertEquals("shared-project", project.name)
        assertEquals(listOf("claude", "codex"), project.agents.map { it.agentId })
        assertEquals(2, project.agents.sumOf { it.sessionCount })
    }

    @Test
    fun `UserMessage events are the prompts and the duplicated response items are ignored`() {
        val projectPath = homeDirectory.resolve("work/titled")
        writeRollout(
            "sessions/2026/08/25/titled.jsonl",
            listOf(
                sessionMetadata("s1", projectPath.toString()),
                responseItem("user", "<environment_context>cwd</environment_context>"),
                responseItem("user", "please fix the build"),
                userMessageEvent("  please   fix the build  "),
                responseItem("assistant", "On it."),
                userMessageEvent("and run the tests"),
            ),
        )

        val session = CodexProjectProvider(codexDirectory()).discover().single()

        assertEquals("please fix the build", session.metadata["firstMessage"])
        assertEquals("2", session.metadata["messageCount"])
    }

    @Test
    fun `session index thread name overrides the first prompt and newest rename wins`() {
        val projectPath = homeDirectory.resolve("work/renamed")
        writeRollout(
            "sessions/2026/08/25/renamed.jsonl",
            listOf(sessionMetadata("renamed-session", projectPath.toString()), userMessageEvent("original prompt")),
        )
        Files.writeString(
            codexDirectory().resolve("session_index.jsonl"),
            listOf(
                """{"id":"renamed-session","thread_name":"Old name","updated_at":"2026-08-25T10:00:00Z"}""",
                "{malformed",
                """{"id":"renamed-session","thread_name":"Ellenőrizd a cache-elést","updated_at":"2026-08-25T11:00:00Z"}""",
            ).joinToString("\n", postfix = "\n"),
        )

        val session = CodexProjectProvider(codexDirectory()).discover().single()

        assertEquals("Ellenőrizd a cache-elést", session.metadata["title"])
        assertEquals("original prompt", session.metadata["firstMessage"])
    }
    @Test
    fun `rollouts without UserMessage events fall back to user response items minus injected context`() {
        val projectPath = homeDirectory.resolve("work/older")
        writeRollout(
            "sessions/2026/08/25/older.jsonl",
            listOf(
                sessionMetadata("s1", projectPath.toString()),
                responseItem("user", "<environment_context>cwd</environment_context>"),
                responseItem("user", "legacy prompt"),
                responseItem("assistant", "Hello"),
            ),
        )

        val session = CodexProjectProvider(codexDirectory()).discover().single()

        assertEquals("legacy prompt", session.metadata["firstMessage"])
        assertEquals("1", session.metadata["messageCount"])
    }

    @Test
    fun `a rollout without any user prompt counts zero and has no first message`() {
        writeRollout(
            "sessions/2026/08/25/empty.jsonl",
            listOf(sessionMetadata("s1", homeDirectory.resolve("work/empty").toString()), responseItem("assistant", "Hi")),
        )

        val session = CodexProjectProvider(codexDirectory()).discover().single()

        assertNull(session.metadata["firstMessage"])
        assertEquals("0", session.metadata["messageCount"])
    }

    @Test
    private fun codexDirectory(): Path = homeDirectory.resolve(".codex")

    private fun writeRollout(
        relativePath: String,
        lines: List<String>,
        modifiedAt: Instant = Instant.parse("2026-08-20T10:30:00Z"),
    ): Path {
        val file = codexDirectory().resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt))
        return file
    }

    private fun sessionMetadata(
        sessionId: String?,
        cwd: String,
        timestamp: String = "2026-08-20T10:00:00Z",
    ): String {
        val id = sessionId?.let { "\"id\":${json(it)}," }.orEmpty()
        return "{\"timestamp\":${json(timestamp)},\"type\":\"session_meta\"," +
            "\"payload\":{$id\"timestamp\":${json(timestamp)}," +
            "\"cwd\":${json(cwd)},\"unknown\":true}}"
    }

    private fun event(timestamp: String): String =
        "{\"timestamp\":${json(timestamp)},\"type\":\"event_msg\",\"payload\":{\"type\":\"metadata-only-fixture\"}}"

    private fun userMessageEvent(text: String): String =
        "{\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\",\"item\":{\"type\":\"UserMessage\"," +
            "\"id\":\"u\",\"content\":[{\"type\":\"text\",\"text\":${json(text)},\"text_elements\":[]}]}}}"

    private fun responseItem(role: String, text: String): String =
        "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":${json(role)}," +
            "\"content\":[{\"type\":\"input_text\",\"text\":${json(text)}}]}}"
}
