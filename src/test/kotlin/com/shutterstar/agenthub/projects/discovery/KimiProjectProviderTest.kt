package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class KimiProjectProviderTest {
    @TempDir
    lateinit var data: Path

    private val created = Instant.parse("2026-10-01T10:00:00Z")
    private val updated = Instant.parse("2026-10-01T12:00:00Z")

    private fun session(
        key: String,
        id: String,
        cwd: String?,
        title: String? = "A title",
        archived: Boolean = false,
        createdMillis: Long = created.toEpochMilli(),
        updatedMillis: Long = updated.toEpochMilli(),
        wire: List<String>? = null,
    ): Path {
        val directory = Files.createDirectories(data.resolve("sessions/$key/$id"))
        val fields = buildList {
            add(""""id":${json(id)}""")
            cwd?.let { add(""""cwd":${json(it)}""") }
            title?.let { add(""""title":${json(it)}""") }
            add(""""archived":$archived""")
            add(""""createdAt":$createdMillis""")
            add(""""updatedAt":$updatedMillis""")
        }
        Files.writeString(directory.resolve("state.json"), fields.joinToString(",", "{", "}"))
        wire?.let {
            val main = Files.createDirectories(directory.resolve("agents/main"))
            Files.writeString(main.resolve("wire.jsonl"), it.joinToString("\n", postfix = "\n"))
        }
        return directory
    }

    private fun turn(input: String, type: String = "turn_begin") = """{"type":"$type","time":1790000000,"userInput":${json(input)}}"""

    @Test
    fun `should read the session state and count the typed user turns of the main agent`() {
        val project = data.resolve("work/app").toString()
        session(
            "wd_app_0123456789ab", "s-1", project,
            wire = listOf("""{"type":"metadata","protocol_version":"1.5","created_at":1}""", turn("Fix the build"), """{"type":"step_begin"}""", turn("Add a test"), turn("   ")),
        )

        val provider = KimiProjectProvider(data)
        val found = provider.discover().single()

        assertEquals("kimi", found.agentId)
        assertEquals("s-1", found.sessionId)
        assertEquals(project, found.rawProjectPath)
        assertEquals(created, found.startedAt)
        assertEquals(updated, found.updatedAt)
        assertEquals("A title", found.metadata["title"])
        assertEquals("2", found.metadata[UserMessageTally.MESSAGE_COUNT_KEY])
        assertEquals("Fix the build", found.metadata[UserMessageTally.FIRST_MESSAGE_KEY])
        assertTrue(found.sourcePath!!.endsWith("s-1"))
    }

    @Test
    fun `should skip archived sessions sessions without a cwd and the index cache directory`() {
        val project = data.resolve("work/mixed").toString()
        session("wd_mixed_aaaaaaaaaaaa", "kept", project)
        session("wd_mixed_aaaaaaaaaaaa", "archived", project, archived = true)
        session("wd_mixed_aaaaaaaaaaaa", "no-cwd", null)
        Files.createDirectories(data.resolve("sessions/.index-cache"))
        Files.writeString(data.resolve("sessions/.index-cache/scan.json"), "{}")

        assertEquals(listOf("kept"), KimiProjectProvider(data).discover().map { it.sessionId })
    }

    @Test
    fun `should accept second based timestamps and survive a malformed state file`() {
        val project = data.resolve("work/seconds").toString()
        session("wd_seconds_bbbbbbbbbbbb", "secs", project, createdMillis = 1_790_000_000, updatedMillis = 1_790_003_600)
        val broken = Files.createDirectories(data.resolve("sessions/wd_seconds_bbbbbbbbbbbb/broken"))
        Files.writeString(broken.resolve("state.json"), "{not json")

        val found = KimiProjectProvider(data).discover().single()

        assertEquals(Instant.ofEpochSecond(1_790_000_000), found.startedAt)
        assertEquals("secs", found.sessionId)
    }

    @Test
    fun `should not count turns of sub agents or other record types`() {
        val project = data.resolve("work/sub").toString()
        val directory = session("wd_sub_cccccccccccc", "sub-1", project, wire = listOf(turn("Main prompt"), turn("Ignored", type = "turn_end")))
        val subAgent = Files.createDirectories(directory.resolve("agents/agent-0"))
        Files.writeString(subAgent.resolve("wire.jsonl"), turn("Sub-agent prompt") + "\n")

        val found = KimiProjectProvider(data).discover().single()

        assertEquals("1", found.metadata[UserMessageTally.MESSAGE_COUNT_KEY])
    }

    @Test
    fun `should be unavailable without a sessions directory`() {
        assertFalse(KimiProjectProvider(data).isAvailable())
        assertTrue(KimiProjectProvider(data).discover().isEmpty())

        Files.createDirectories(data.resolve("sessions"))

        assertTrue(KimiProjectProvider(data).isAvailable())
        assertTrue(KimiProjectProvider(data).discover().isEmpty())
    }
}
