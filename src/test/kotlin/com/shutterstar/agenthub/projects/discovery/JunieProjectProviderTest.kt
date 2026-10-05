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

class JunieProjectProviderTest {
    @TempDir
    lateinit var home: Path

    private val created = Instant.parse("2026-10-05T18:04:32Z")
    private val updated = Instant.parse("2026-10-05T18:10:00Z")

    private fun summaryJson(id: String, projectDir: String?) = buildList {
        add(""""sessionId":${json(id)}""")
        add(""""createdAt":${created.toEpochMilli()}""")
        add(""""updatedAt":${updated.toEpochMilli()}""")
        projectDir?.let { add(""""projectDir":${json(it)}""") }
        add(""""lifecycle":"FINISHED"""")
    }.joinToString(",", "{", "}")

    private fun prompt(text: String, presentable: String = text) =
        """{"kind":"UserPromptEvent","requestId":"r","prompt":${json(text)},"presentablePrompt":${json(presentable)},"requiresConfirmation":false,"timestampMs":1}"""

    private fun session(id: String, projectDir: String?, events: List<String>? = null, summary: Boolean = true): Path {
        val directory = Files.createDirectories(home.resolve("sessions/$id"))
        Files.writeString(directory.resolve("transcript.md"), "# Session transcript\n")
        if (summary) Files.writeString(directory.resolve("summary.json"), summaryJson(id, projectDir))
        events?.let { Files.writeString(directory.resolve("events.jsonl"), it.joinToString("\n", postfix = "\n")) }
        return directory
    }

    @Test
    fun `should read the summary and count the user prompt events`() {
        val project = home.resolve("work/app").toString()
        session(
            "session-261005-180432-abcd", project,
            events = listOf(
                """{"kind":"SkillsStatusEvent","newSkills":["a"],"timestampMs":1}""",
                prompt("<user_input mode=\"act\">Fix the build</user_input>", presentable = "Fix the build"),
                """{"kind":"SessionA2uxEvent","taskId":"t","timestampMs":2}""",
                prompt("Add a test"),
            ),
        )

        val found = JunieProjectProvider(home).discover().single()

        assertEquals("junie", found.agentId)
        assertEquals("session-261005-180432-abcd", found.sessionId)
        assertEquals(project, found.rawProjectPath)
        assertEquals(created, found.startedAt)
        assertEquals(updated, found.updatedAt)
        assertEquals("2", found.metadata[UserMessageTally.MESSAGE_COUNT_KEY])
        assertEquals("Fix the build", found.metadata[UserMessageTally.FIRST_MESSAGE_KEY])
        assertNull(found.metadata["title"])
        assertTrue(found.sourcePath!!.endsWith("session-261005-180432-abcd"))
    }

    @Test
    fun `should skip empty sessions and sessions with a malformed summary`() {
        val project = home.resolve("work/mixed").toString()
        session("session-1-kept", project, events = listOf(prompt("Go")))
        // A session that never ran a task: only a transcript, no summary.
        session("session-2-empty", null, summary = false)
        // A readable event stream without any prompt.
        session("session-3-noprompt", project, events = listOf("""{"kind":"TaskStartedEvent","taskId":"t","timestampMs":1}"""))
        // Malformed and incomplete summaries.
        Files.writeString(session("session-4-broken", project).resolve("summary.json"), "{not json")
        session("session-5-nodir", null, events = listOf(prompt("Lost")))

        assertEquals(listOf("session-1-kept"), JunieProjectProvider(home).discover().map { it.sessionId })
    }

    @Test
    fun `should fall back to the index when a session folder has no summary`() {
        val project = home.resolve("work/indexed").toString()
        session("session-6-indexed", null, events = listOf(prompt("From the index")), summary = false)
        Files.writeString(home.resolve("sessions/index.jsonl"), summaryJson("session-6-indexed", project) + "\n" + "garbage\n")

        val found = JunieProjectProvider(home).discover().single()

        assertEquals(project, found.rawProjectPath)
        assertEquals("From the index", found.metadata[UserMessageTally.FIRST_MESSAGE_KEY])
    }

    @Test
    fun `should be unavailable without a sessions directory`() {
        assertFalse(JunieProjectProvider(home).isAvailable())
        assertTrue(JunieProjectProvider(home).discover().isEmpty())

        Files.createDirectories(home.resolve("sessions"))

        assertTrue(JunieProjectProvider(home).isAvailable())
        assertTrue(JunieProjectProvider(home).discover().isEmpty())
    }
}
