package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class CopilotProjectProviderTest {
    @TempDir
    lateinit var homeDirectory: Path

    @Test
    fun `discovers one valid session from top level metadata`() {
        val projectPath = homeDirectory.resolve("work/project with spaces")
        val startedAt = Instant.parse("2026-08-20T10:15:30Z")
        val modifiedAt = Instant.parse("2026-08-20T11:00:00Z")
        val file = writeSession(
            sessionKey = "encoded-project",
            fileName = "fallback-id.jsonl",
            lines = listOf(
                """{"type":"mode","id":"session-one"}""",
                metadataLine("session-one", projectPath.toString(), startedAt.toString()),
            ),
            modifiedAt = modifiedAt,
        )

        val session = CopilotProjectProvider(homeDirectory).discover().single()

        assertEquals("copilot", session.agentId)
        assertEquals("session-one", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
        assertEquals(startedAt, session.startedAt)
        assertEquals(modifiedAt, session.updatedAt)
        assertEquals(file.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    @Test
    fun `legacy header title is retained and a named session state survives deduplication`() {
        val project = homeDirectory.resolve("work/named")
        val legacy = """{"id":"named","cwd":${json(project.toString())},"timestamp":"2026-08-20T10:00:00Z","title":"Legacy name"}"""
        writeSession("named-project", "named.jsonl", listOf(legacy.replace(",\"title\":\"Legacy name\"", "")), Instant.parse("2027-01-01T00:00:00Z"))
        writeSession("legacy-only", "legacy.jsonl", listOf(legacy.replace("named", "legacy").replace("Legacy name", "Legacy title")))
        val state = Files.createDirectories(homeDirectory.resolve(".copilot/session-state/named"))
        Files.writeString(state.resolve("workspace.yaml"), "id: named\ncwd: $project\nname: Custom name\n")

        val sessions = CopilotProjectProvider(homeDirectory).discover().associateBy { it.sessionId }
        assertEquals("Custom name", sessions.getValue("named").metadata["title"])
        assertEquals("Legacy title", sessions.getValue("legacy").metadata["title"])
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

        val sessions = CopilotProjectProvider(homeDirectory).discover()

        assertEquals(listOf("recovered"), sessions.map { it.sessionId })
    }

    @Test
    fun `missing session id falls back to file name while missing cwd is skipped`() {
        val projectPath = homeDirectory.resolve("work/fallback")
        writeSession("project", "file-session-id.jsonl", listOf(metadataLine(null, projectPath.toString())))
        writeSession("project", "missing-cwd.jsonl", listOf("""{"id":"no-project"}"""))

        val sessions = CopilotProjectProvider(homeDirectory).discover()

        assertEquals(1, sessions.size)
        assertEquals("file-session-id", sessions.single().sessionId)
        assertEquals(projectPath.toString(), sessions.single().rawProjectPath)
    }

    @Test
    fun `unknown and nested fields cannot replace top level metadata`() {
        val projectPath = homeDirectory.resolve("work/real")
        val line = """{"type":"user","message":{"cwd":"C:\\wrong","id":"nested"},""" +
            """"future":[{"timestamp":"bad"}],"cwd":${json(projectPath.toString())},""" +
            """"id":"real","timestamp":"2026-08-21T12:00:00Z","newField":true}"""
        writeSession("project", "unknown-fields.jsonl", listOf(line))

        val session = CopilotProjectProvider(homeDirectory).discover().single()

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

        val session = CopilotProjectProvider(homeDirectory).discover().single()

        assertNull(session.startedAt)
        assertEquals(modifiedAt, session.updatedAt)
        assertFalse(Files.exists(Path.of(session.rawProjectPath!!)))
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

        val sessions = CopilotProjectProvider(homeDirectory).discover()

        assertEquals(listOf("parent"), sessions.map { it.sessionId })
    }

    @Test
    fun `discovers current session state workspace metadata`() {
        val sessionDirectory = Files.createDirectories(
            homeDirectory.resolve(".copilot/session-state/current-session"),
        )
        val workspace = sessionDirectory.resolve("workspace.yaml")
        Files.writeString(
            workspace,
            """
            cwd: "${homeDirectory.resolve("work/current")}"
            git_root: "${homeDirectory.resolve("work/current")}"
            branch: main
            """.trimIndent(),
        )

        val session = CopilotProjectProvider(homeDirectory).discover().single()

        assertEquals("current-session", session.sessionId)
        assertEquals(homeDirectory.resolve("work/current").toString(), session.rawProjectPath)
        assertEquals(workspace.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    @Test
    fun `discovers legacy history session state and skips incomplete entries`() {
        val history = Files.createDirectories(
            homeDirectory.resolve(".copilot/history-session-state/legacy-session"),
        )
        Files.writeString(history.resolve("workspace.yaml"), "cwd: ${homeDirectory.resolve("work/legacy")}\n")
        Files.createDirectories(homeDirectory.resolve(".copilot/session-state/incomplete"))

        val sessions = CopilotProjectProvider(homeDirectory).discover()

        assertEquals(listOf("legacy-session"), sessions.map { it.sessionId })
        assertEquals(homeDirectory.resolve("work/legacy").toString(), sessions.single().rawProjectPath)
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

        val session = CopilotProjectProvider(homeDirectory).discover().single()

        assertEquals("bounded", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
    }

    @Test
    fun `session state prompts come from the user message events next to the workspace`() {
        val sessionDirectory = Files.createDirectories(homeDirectory.resolve(".copilot/session-state/with-events"))
        Files.writeString(sessionDirectory.resolve("workspace.yaml"), "cwd: ${homeDirectory.resolve("work/events")}\n")
        Files.writeString(
            sessionDirectory.resolve("events.jsonl"),
            listOf(
                """{"type":"session.start","data":{},"id":"1"}""",
                """{"type":"user.message","data":{"content":"  explain   this repo ","interactionId":"i"},"id":"2"}""",
                """{"type":"assistant.message","data":{"content":"It is a plugin."},"id":"3"}""",
                """{"type":"user.message","data":{"content":"thanks"},"id":"4"}""",
            ).joinToString("\n"),
        )

        val session = CopilotProjectProvider(homeDirectory).discover().single()

        assertEquals("2", session.metadata["messageCount"])
        assertEquals("explain this repo", session.metadata["firstMessage"])
    }

    @Test
    fun `a session state without an events file never received a prompt`() {
        val sessionDirectory = Files.createDirectories(homeDirectory.resolve(".copilot/session-state/no-events"))
        Files.writeString(sessionDirectory.resolve("workspace.yaml"), "cwd: ${homeDirectory.resolve("work/none")}\n")

        val session = CopilotProjectProvider(homeDirectory).discover().single()

        assertEquals("0", session.metadata["messageCount"])
        assertNull(session.metadata["firstMessage"])
    }

    @Test
    fun `workspace name and timestamps fill the title and the date range`() {
        val sessionDirectory = Files.createDirectories(homeDirectory.resolve(".copilot/session-state/named"))
        Files.writeString(
            sessionDirectory.resolve("workspace.yaml"),
            """
            id: named
            cwd: ${homeDirectory.resolve("work/named")}
            name: Refactor parser
            created_at: 2026-09-20T08:05:00.000Z
            updated_at: 2026-09-20T09:05:00.000Z
            """.trimIndent(),
        )
        Files.setLastModifiedTime(sessionDirectory.resolve("workspace.yaml"), FileTime.from(Instant.parse("2026-09-20T09:00:00Z")))

        val session = CopilotProjectProvider(homeDirectory).discover().single()

        assertEquals("Refactor parser", session.metadata["title"])
        assertEquals(Instant.parse("2026-09-20T08:05:00Z"), session.startedAt)
        assertEquals(Instant.parse("2026-09-20T09:05:00Z"), session.updatedAt)
    }

    private fun sessionsDirectory(): Path = homeDirectory.resolve(".copilot/sessions")

    private fun writeSession(
        sessionKey: String,
        fileName: String,
        lines: List<String>,
        modifiedAt: Instant = Instant.parse("2026-08-20T11:00:00Z"),
    ): Path {
        val directory = Files.createDirectories(sessionsDirectory().resolve(sessionKey))
        val file = directory.resolve(fileName)
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt))
        return file
    }

    private fun metadataLine(
        id: String?,
        cwd: String,
        timestamp: String = "2026-08-20T10:15:30Z",
    ): String {
        val idField = id?.let { "\"id\":${json(it)}," }.orEmpty()
        return "{$idField\"cwd\":${json(cwd)},\"timestamp\":${json(timestamp)},\"type\":\"user\"}"
    }
}
