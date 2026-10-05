package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class VibeProjectProviderTest {
    @TempDir
    lateinit var home: Path

    private fun session(
        name: String,
        id: String,
        cwd: String?,
        title: String? = null,
        parent: String? = null,
        archived: String? = null,
        messages: List<String>? = null,
    ): Path {
        val directory = Files.createDirectories(home.resolve("logs/session/$name"))
        val fields = buildList {
            add(""""session_id": ${json(id)}""")
            add(""""parent_session_id": ${parent?.let(::json) ?: "null"}""")
            add(""""start_time": "2026-10-05T18:12:17.583216+00:00"""")
            add(""""end_time": "2026-10-05T18:18:25.482374+00:00"""")
            add(""""archived_at": ${archived?.let(::json) ?: "null"}""")
            add(""""title": ${title?.let(::json) ?: "null"}""")
            add(""""environment": {"working_directory": ${cwd?.let(::json) ?: "null"}}""")
            add(""""system_prompt": {"role": "system", "content": "not read", "tool_calls": null}""")
            add(""""stats": {"steps": 3, "session_cost": 0.01}""")
        }
        Files.writeString(directory.resolve("meta.json"), fields.joinToString(",\n", "{\n", "\n}"))
        messages?.let { Files.writeString(directory.resolve("messages.jsonl"), it.joinToString("\n", postfix = "\n")) }
        return directory
    }

    private fun message(role: String, content: String, injected: Boolean = false) =
        """{"role": "$role", "content": ${json(content)}, "injected": ${injected}, "message_id": "m-$content"}"""

    @Test
    fun `should read the session meta and count only typed user messages`() {
        val project = home.resolve("work/app").toString()
        session(
            "session_20261005_181217_7cae594d", "7cae594d-625b-7c61-f83f-40af4bae37bd", project, title = "Fix build",
            messages = listOf(
                message("user", "Fix the build"),
                message("assistant", "Done"),
                message("user", "<system-reminder>context</system-reminder>", injected = true),
                message("user", "Add a test"),
                message("user", "   "),
            ),
        )

        val found = VibeProjectProvider(home).discover().single()

        assertEquals("vibe", found.agentId)
        assertEquals("7cae594d-625b-7c61-f83f-40af4bae37bd", found.sessionId)
        assertEquals(project, found.rawProjectPath)
        assertEquals(Instant.parse("2026-10-05T18:12:17.583216Z"), found.startedAt)
        assertEquals(Instant.parse("2026-10-05T18:18:25.482374Z"), found.updatedAt)
        assertEquals("Fix build", found.metadata["title"])
        assertEquals("2", found.metadata[UserMessageTally.MESSAGE_COUNT_KEY])
        assertEquals("Fix the build", found.metadata[UserMessageTally.FIRST_MESSAGE_KEY])
        assertTrue(found.sourcePath!!.endsWith("session_20261005_181217_7cae594d"))
    }

    @Test
    fun `should accept compact json and a session without messages or title`() {
        val project = home.resolve("work/compact").toString()
        val directory = session("session_20261001_100000_aaaaaaaa", "aaaaaaaa-0000-0000-0000-000000000001", project)
        Files.writeString(directory.resolve("messages.jsonl"), """{"role":"user","content":"Compact","injected":false}""" + "\n")
        session("session_20261001_110000_bbbbbbbb", "bbbbbbbb-0000-0000-0000-000000000002", project)

        val found = VibeProjectProvider(home).discover().associateBy { it.sessionId }

        assertEquals("1", found.getValue("aaaaaaaa-0000-0000-0000-000000000001").metadata[UserMessageTally.MESSAGE_COUNT_KEY])
        assertNull(found.getValue("bbbbbbbb-0000-0000-0000-000000000002").metadata["title"])
        assertNull(found.getValue("bbbbbbbb-0000-0000-0000-000000000002").metadata[UserMessageTally.MESSAGE_COUNT_KEY])
    }

    @Test
    fun `should skip sub-agent and archived sessions, sessions without a cwd, malformed meta and the active directory`() {
        val project = home.resolve("work/mixed").toString()
        session("session_20261001_100000_11111111", "kept", project)
        session("session_20261001_100001_22222222", "child", project, parent = "kept")
        session("session_20261001_100002_33333333", "archived", project, archived = "2026-10-02T00:00:00+00:00")
        session("session_20261001_100003_44444444", "no-cwd", null)
        Files.writeString(Files.createDirectories(home.resolve("logs/session/session_20261001_100004_55555555")).resolve("meta.json"), "{not json")
        Files.createDirectories(home.resolve("logs/session/active"))
        Files.writeString(home.resolve("logs/session/active/x.lock"), "{}")

        assertEquals(listOf("kept"), VibeProjectProvider(home).discover().map { it.sessionId })
    }

    @Test
    fun `should be unavailable without a session directory`() {
        assertFalse(VibeProjectProvider(home).isAvailable())
        assertTrue(VibeProjectProvider(home).discover().isEmpty())

        Files.createDirectories(home.resolve("logs/session"))

        assertTrue(VibeProjectProvider(home).isAvailable())
        assertTrue(VibeProjectProvider(home).discover().isEmpty())
    }
}
