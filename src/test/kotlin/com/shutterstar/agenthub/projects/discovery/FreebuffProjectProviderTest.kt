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

class FreebuffProjectProviderTest {
    @TempDir
    lateinit var config: Path

    private fun chat(
        folder: String,
        chatId: String,
        projectRoot: String?,
        messages: String?,
        firstPrompt: String? = null,
    ): Path {
        val directory = Files.createDirectories(config.resolve("projects/$folder/chats/$chatId"))
        messages?.let { Files.writeString(directory.resolve("chat-messages.json"), it) }
        if (firstPrompt != null) {
            Files.writeString(directory.resolve("chat-meta.json"), """{"messageCount":2,"firstPrompt":${json(firstPrompt)},"messagesSize":1,"messagesMtimeMs":1}""")
        }
        if (projectRoot != null) {
            // The real file is >1 MB: the project root is the first key of the file context, followed by a long file tree.
            val runState = """{"sessionState":{"fileContext":{"projectRoot":${json(projectRoot)},"cwd":${json(projectRoot)},"fileTree":[""" +
                "\"x\",".repeat(20_000) + """"y"]}}}"""
            Files.writeString(directory.resolve("run-state.json"), runState)
        }
        return directory
    }

    private fun message(variant: String, content: String) = """{"id":"m-$variant","variant":"$variant","content":${json(content)},"blocks":[],"timestamp":"t"}"""

    @Test
    fun `should read the chat folder with the project root of the run state and the typed prompts`() {
        val project = config.resolve("work/PromptForge").toString()
        val directory = chat(
            "PromptForge", "2026-10-05T18-07-03.513Z", project,
            "[" + listOf(message("ai", ""), message("user", "Fix it"), message("ai", "ok"), message("user", "More")).joinToString(",") + "]",
            firstPrompt = "Fix it",
        )

        val found = FreebuffProjectProvider(config).discover().single()

        assertEquals("freebuff", found.agentId)
        assertEquals("2026-10-05T18-07-03.513Z", found.sessionId)
        assertEquals(project, found.rawProjectPath)
        assertEquals(Instant.parse("2026-10-05T18:07:03.513Z"), found.startedAt)
        assertEquals(Files.getLastModifiedTime(directory.resolve("chat-messages.json")).toInstant(), found.updatedAt)
        assertEquals("2", found.metadata[UserMessageTally.MESSAGE_COUNT_KEY])
        assertEquals("Fix it", found.metadata[UserMessageTally.FIRST_MESSAGE_KEY])
    }

    @Test
    fun `should unescape Windows project roots`() {
        val chatId = "2026-10-05T18-07-03.513Z"
        chat("app", chatId, """K:\IdeaProjects\Sub "dir"\app""", "[" + message("user", "Hi") + "]")

        assertEquals("""K:\IdeaProjects\Sub "dir"\app""", FreebuffProjectProvider(config).discover().single().rawProjectPath)
    }

    @Test
    fun `should not count user markers that are only text inside a message`() {
        val project = config.resolve("work/text").toString()
        val quoted = message("ai", """the line "variant":"user" appears in a log""")
        chat("text", "2026-10-05T10-00-00.000Z", project, "[" + quoted + "," + message("user", "Real") + "]")

        assertEquals("1", FreebuffProjectProvider(config).discover().single().metadata[UserMessageTally.MESSAGE_COUNT_KEY])
    }

    @Test
    fun `should count a marker split across read buffers`() {
        val project = config.resolve("work/big").toString()
        val filler = message("ai", "x".repeat(100_000))
        chat("big", "2026-10-05T11-00-00.000Z", project, "[" + filler + "," + message("user", "Hit") + "," + filler + "," + message("user", "Two") + "]")

        assertEquals("2", FreebuffProjectProvider(config).discover().single().metadata[UserMessageTally.MESSAGE_COUNT_KEY])
    }

    @Test
    fun `should skip chats without messages a run state a typed prompt or a project root, and ignore the empty-chat placeholder`() {
        val project = config.resolve("work/mixed").toString()
        chat("mixed", "2026-10-05T12-00-00.000Z", project, "[" + message("user", "Kept") + "]", firstPrompt = "(empty chat)")
        chat("mixed", "2026-10-05T12-00-01.000Z", project, null)
        chat("mixed", "2026-10-05T12-00-02.000Z", null, "[" + message("user", "No run state") + "]")
        chat("mixed", "2026-10-05T12-00-03.000Z", project, "[" + message("ai", "Only the assistant") + "]")
        chat("mixed", "2026-10-05T12-00-04.000Z", project, "[not json")
        Files.writeString(
            Files.createDirectories(config.resolve("projects/mixed/chats/2026-10-05T12-00-05.000Z")).resolve("log.jsonl"),
            "{\"level\":\"WARN\"}\n",
        )

        val found = FreebuffProjectProvider(config).discover()

        assertEquals(listOf("2026-10-05T12-00-00.000Z"), found.map { it.sessionId })
        assertNull(found.single().metadata[UserMessageTally.FIRST_MESSAGE_KEY].takeIf { it == "(empty chat)" })
    }

    @Test
    fun `should be unavailable without a projects directory`() {
        assertFalse(FreebuffProjectProvider(config).isAvailable())
        assertTrue(FreebuffProjectProvider(config).discover().isEmpty())

        Files.createDirectories(config.resolve("projects"))

        assertTrue(FreebuffProjectProvider(config).isAvailable())
        assertTrue(FreebuffProjectProvider(config).discover().isEmpty())
    }
}
