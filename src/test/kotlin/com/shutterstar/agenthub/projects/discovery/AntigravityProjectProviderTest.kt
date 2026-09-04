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

class AntigravityProjectProviderTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `missing and empty storage return no sessions`() {
        val provider = AntigravityProjectProvider(dataDirectory())

        assertFalse(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())

        Files.createDirectories(dataDirectory().resolve("brain"))
        assertTrue(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())
    }

    @Test
    fun `discovers valid session from brain transcript jsonl`() {
        val projectPath = tempDirectory.resolve("work/antigravity-project with spaces")
        val startedAt = Instant.parse("2026-08-30T10:00:00Z")
        val updatedAt = Instant.parse("2026-08-30T12:00:00Z")
        val file = writeBrainTranscript(
            conversationId = "conv-1234",
            lines = listOf(
                """{"step_index":0,"type":"USER_INPUT","created_at":"$startedAt","content":"Hello","cwd":${json(projectPath.toString())}}""",
                """{"step_index":1,"type":"PLANNER_RESPONSE","created_at":"$updatedAt","content":"Ready"}""",
            ),
            modifiedAt = Instant.parse("2026-08-30T10:05:00Z"),
        )

        val session = AntigravityProjectProvider(dataDirectory()).discover().single()

        assertEquals("antigravity", session.agentId)
        assertEquals("conv-1234", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
        assertEquals(startedAt, session.startedAt)
        assertEquals(updatedAt, session.updatedAt)
        assertEquals(file.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    @Test
    fun `discovers valid session from metadata json`() {
        val projectPath = tempDirectory.resolve("work/json-project")
        val startedAt = Instant.parse("2026-08-28T09:00:00Z")
        val updatedAt = Instant.parse("2026-08-28T11:30:00Z")
        val file = writeSessionJson(
            conversationId = "conv-json",
            json = """{
                "sessionId": "conv-json",
                "workspace": ${json(projectPath.toString())},
                "title": "My Antigravity Task",
                "createdAt": "$startedAt",
                "updatedAt": "$updatedAt"
            }""".trimIndent(),
            modifiedAt = Instant.parse("2026-08-28T10:00:00Z"),
        )

        val session = AntigravityProjectProvider(dataDirectory()).discover().single()

        assertEquals("antigravity", session.agentId)
        assertEquals("conv-json", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
        assertEquals(startedAt, session.startedAt)
        assertEquals(updatedAt, session.updatedAt)
        assertEquals("My Antigravity Task", session.metadata["title"])
        assertEquals(file.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    @Test
    fun `discovers multiple sessions across brain and conversation directories`() {
        val firstProject = tempDirectory.resolve("work/first")
        val secondProject = tempDirectory.resolve("work/second")

        writeBrainTranscript(
            conversationId = "brain-one",
            lines = listOf("""{"type":"USER_INPUT","cwd":${json(firstProject.toString())},"timestamp":"2026-08-25T10:00:00Z"}"""),
        )
        writeBrainTranscript(
            conversationId = "brain-two",
            lines = listOf("""{"type":"USER_INPUT","cwd":${json(firstProject.toString())},"timestamp":"2026-08-25T11:00:00Z"}"""),
        )
        writeConversationJsonl(
            fileName = "conv-three.jsonl",
            lines = listOf("""{"sessionId":"conv-three","workspace":${json(secondProject.toString())},"created_at":"2026-08-26T10:00:00Z"}"""),
        )

        val sessions = AntigravityProjectProvider(dataDirectory()).discover()

        assertEquals(3, sessions.size)
        assertEquals(2, sessions.count { it.rawProjectPath == firstProject.toString() })
        assertEquals(1, sessions.count { it.rawProjectPath == secondProject.toString() })
    }

    @Test
    fun `handles ISO-8601 with timezone offsets and epoch timestamps`() {
        val projectPath = tempDirectory.resolve("work/offset-project")
        val file = writeBrainTranscript(
            conversationId = "offset-conv",
            lines = listOf(
                """{"type":"USER_INPUT","created_at":"2026-08-31T18:26:18+02:00","cwd":${json(projectPath.toString())}}""",
                """{"type":"PLANNER_RESPONSE","created_at":"2026-08-31T18:30:00+02:00"}""",
            ),
            modifiedAt = Instant.parse("2026-08-31T16:20:00Z"),
        )

        val session = AntigravityProjectProvider(dataDirectory()).discover().single()

        assertEquals(Instant.parse("2026-08-31T16:26:18Z"), session.startedAt)
        assertEquals(Instant.parse("2026-08-31T16:30:00Z"), session.updatedAt)
    }

    @Test
    fun `malformed and incomplete transcript lines do not abort discovery`() {
        val projectPath = tempDirectory.resolve("work/valid")
        writeBrainTranscript("broken-conv", listOf("not-json", "{unfinished"))
        writeBrainTranscript(
            "recoverable-conv",
            listOf("{broken", """{"type":"USER_INPUT","cwd":${json(projectPath.toString())},"created_at":"2026-08-27T10:00:00Z"}"""),
        )

        val sessions = AntigravityProjectProvider(dataDirectory()).discover()

        assertEquals(listOf("recoverable-conv"), sessions.map { it.sessionId })
    }

    @Test
    fun `missing session id falls back to directory name or file name`() {
        val projectPath = tempDirectory.resolve("work/fallback")
        writeBrainTranscript(
            conversationId = "inferred-dir-id",
            lines = listOf("""{"type":"USER_INPUT","cwd":${json(projectPath.toString())}}"""),
        )
        writeConversationJsonl(
            fileName = "inferred-file-id.jsonl",
            lines = listOf("""{"type":"USER_INPUT","cwd":${json(projectPath.toString())}}"""),
        )

        val sessions = AntigravityProjectProvider(dataDirectory()).discover()

        assertEquals(2, sessions.size)
        assertTrue(sessions.any { it.sessionId == "inferred-dir-id" })
        assertTrue(sessions.any { it.sessionId == "inferred-file-id" })
    }

    @Test
    fun `missing cwd or workspace is skipped`() {
        writeBrainTranscript("no-cwd", listOf("""{"type":"USER_INPUT","created_at":"2026-08-20T10:00:00Z"}"""))
        writeSessionJson("no-cwd-json", """{"sessionId":"no-cwd","createdAt":"2026-08-20T10:00:00Z"}""")

        val sessions = AntigravityProjectProvider(dataDirectory()).discover()

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `duplicate session ids merge metadata and keep the newest activity`() {
        val oldProject = tempDirectory.resolve("work/old")
        val newProject = tempDirectory.resolve("work/new")

        writeSessionJson(
            conversationId = "duplicate-id",
            json = """{
                "sessionId": "duplicate-id",
                "workspace": ${json(oldProject.toString())},
                "title": "Merged Title",
                "createdAt": "2026-08-20T10:00:00Z"
            }""".trimIndent(),
            modifiedAt = Instant.parse("2026-08-20T10:00:00Z"),
        )

        val newestFile = writeBrainTranscript(
            conversationId = "duplicate-id",
            lines = listOf(
                """{"step_index":0,"type":"USER_INPUT","created_at":"2026-08-22T10:00:00Z","cwd":${json(newProject.toString())}}""",
                """{"step_index":1,"type":"PLANNER_RESPONSE","created_at":"2026-08-22T12:00:00Z"}""",
            ),
            modifiedAt = Instant.parse("2026-08-22T12:00:00Z"),
        )

        val session = AntigravityProjectProvider(dataDirectory()).discover().single()

        assertEquals("duplicate-id", session.sessionId)
        assertEquals(newProject.toString(), session.rawProjectPath)
        assertEquals(Instant.parse("2026-08-22T12:00:00Z"), session.updatedAt)
        assertEquals("Merged Title", session.metadata["title"])
    }

    @Test
    fun `files in scratch directory are ignored`() {
        val projectPath = tempDirectory.resolve("work/scratch-project")
        val scratchDir = Files.createDirectories(dataDirectory().resolve("brain/my-conv/scratch"))
        Files.writeString(
            scratchDir.resolve("scratch-script.jsonl"),
            """{"type":"USER_INPUT","cwd":${json(projectPath.toString())},"timestamp":"2026-08-20T10:00:00Z"}""",
        )

        val sessions = AntigravityProjectProvider(dataDirectory()).discover()

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `ignored configuration files like settings json are not treated as sessions`() {
        val dir = Files.createDirectories(dataDirectory())
        Files.writeString(dir.resolve("settings.json"), """{"model":"gemini-3.7-flash","workspace":"/some/dir"}""")
        Files.writeString(dir.resolve("plugins.json"), """{"plugins":[]}""")
        Files.writeString(dir.resolve("mcp_config.json"), """{"mcpServers":{}}""")

        val sessions = AntigravityProjectProvider(dataDirectory()).discover()

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `Antigravity, Claude, and Codex histories aggregate into one project`() {
        val projectPath = Files.createDirectories(tempDirectory.resolve("work/shared-project"))
        val claudeDir = Files.createDirectories(tempDirectory.resolve(".claude/projects/shared"))
        val codexDir = Files.createDirectories(tempDirectory.resolve(".codex/sessions/2026/08/20"))

        writeBrainTranscript(
            conversationId = "agy-session",
            lines = listOf("""{"type":"USER_INPUT","cwd":${json(projectPath.toString())},"created_at":"2026-08-20T08:00:00Z"}"""),
        )
        Files.writeString(
            claudeDir.resolve("claude-session.jsonl"),
            """{"sessionId":"claude-session","cwd":${json(projectPath.toString())},"timestamp":"2026-08-20T10:00:00Z"}""",
        )
        Files.writeString(
            codexDir.resolve("codex-session.jsonl"),
            """{"timestamp":"2026-08-20T12:00:00Z","type":"session_meta","payload":{"id":"codex-session","cwd":${json(projectPath.toString())}}}""",
        )

        val service = ProjectDiscoveryService(
            providers = listOf(
                AntigravityProjectProvider(dataDirectory()),
                ClaudeProjectProvider(tempDirectory),
                CodexProjectProvider(tempDirectory.resolve(".codex")),
            ),
            projectResolver = ProjectResolver { null },
        )

        val project = service.discoverProjects().single()

        assertEquals("shared-project", project.name)
        assertEquals(listOf("antigravity", "claude", "codex"), project.agents.map { it.agentId })
        assertEquals(3, project.agents.sumOf { it.sessionCount })
    }

    @Test
    fun `discovers session from tool call args and normalizes project path`() {
        val projectPath = tempDirectory.resolve("work/args-project")
        val startedAt = Instant.parse("2026-08-30T10:00:00Z")
        writeBrainTranscript(
            conversationId = "conv-args",
            lines = listOf(
                """{"step_index":0,"type":"USER_INPUT","created_at":"$startedAt","content":"Find files"}""",
                """{"step_index":1,"type":"PLANNER_RESPONSE","created_at":"2026-08-30T10:01:00Z","tool_calls":[{"name":"find_by_name","args":{"SearchDirectory":${json(projectPath.toString())}}}]}""",
            ),
        )

        val session = AntigravityProjectProvider(dataDirectory()).discover().single()

        assertEquals("conv-args", session.sessionId)
        assertEquals(projectPath.toString(), session.rawProjectPath)
    }

    @Test
    fun `discovers sessions from conversation_metadata json with WorkspaceURIs and normalizes file URI`() {
        val projectPath = tempDirectory.resolve("work/metadata-project")
        val cacheDir = Files.createDirectories(dataDirectory().resolve("cache"))
        val fileUri = "file:///" + projectPath.toString().replace('\\', '/')
        val metadataJson = """{
            "conversations": {
                "conv-meta-1": {
                    "summary": {
                        "ID": "conv-meta-1",
                        "Title": "Code Review Task",
                        "Preview": "Reviewing PR",
                        "UpdatedAt": "2026-08-30T14:00:00Z",
                        "WorkspaceURIs": [
                            "$fileUri"
                        ]
                    }
                }
            }
        }""".trimIndent()
        Files.writeString(cacheDir.resolve("conversation_metadata.json"), metadataJson)

        val session = AntigravityProjectProvider(dataDirectory()).discover().single()

        assertEquals("conv-meta-1", session.sessionId)
        assertEquals("Code Review Task", session.metadata["title"])
        assertEquals(Instant.parse("2026-08-30T14:00:00Z"), session.updatedAt)
        // Ensure file:/// is stripped
        assertFalse(session.rawProjectPath.orEmpty().startsWith("file:"))
        assertTrue(session.rawProjectPath.orEmpty().contains("metadata-project"))
    }

    private fun dataDirectory(): Path = tempDirectory.resolve("antigravity-data")

    private fun writeBrainTranscript(
        conversationId: String,
        lines: List<String>,
        modifiedAt: Instant = Instant.parse("2026-08-30T10:00:00Z"),
    ): Path {
        val dir = Files.createDirectories(dataDirectory().resolve("brain/$conversationId/.system_generated/logs"))
        val file = dir.resolve("transcript.jsonl")
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt))
        return file
    }

    private fun writeSessionJson(
        conversationId: String,
        json: String,
        modifiedAt: Instant = Instant.parse("2026-08-30T10:00:00Z"),
    ): Path {
        val dir = Files.createDirectories(dataDirectory().resolve("brain/$conversationId"))
        val file = dir.resolve("metadata.json")
        Files.writeString(file, json)
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt))
        return file
    }

    private fun writeConversationJsonl(
        fileName: String,
        lines: List<String>,
        modifiedAt: Instant = Instant.parse("2026-08-30T10:00:00Z"),
    ): Path {
        val dir = Files.createDirectories(dataDirectory().resolve("conversations"))
        val file = dir.resolve(fileName)
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt))
        return file
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
