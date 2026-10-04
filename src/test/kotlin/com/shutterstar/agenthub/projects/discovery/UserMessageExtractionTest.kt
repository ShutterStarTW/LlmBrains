package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * First user message and user-only prompt count for the providers whose transcript formats are
 * covered here in one place (Claude, Codex and Copilot have theirs in their own provider tests).
 * Every fixture mirrors a record shape observed in the agent's real on-disk storage.
 */
class UserMessageExtractionTest {
    @TempDir
    lateinit var tempDirectory: Path

    private val project: String get() = tempDirectory.resolve("work/project").toString()

    @Test
    fun `Cursor reads the newest-first prompt history and never the chat database`() {
        val session = write(
            ".cursor/chats/ws/s1/meta.json",
            """{"schemaVersion":1,"createdAtMs":1767225600000,"hasConversation":true,"title":"Generated","cwd":${json(project)}}""",
        ).parent
        write(session.resolve("prompt_history.json"), """["third", "second", "  first\n prompt "]""")
        write(session.resolve("store.db"), "not a database")

        val raw = CursorProjectProvider(tempDirectory.resolve(".cursor")).discover().single()

        assertEquals("3", raw.metadata["messageCount"])
        assertEquals("first prompt", raw.metadata["firstMessage"])
        assertEquals("Generated", raw.metadata["title"])
    }

    @Test
    fun `Grok skips synthetic records and injected context blocks`() {
        val session = write(
            ".grok/sessions/ws/g1/summary.json",
            """{"info":{"id":"g1","cwd":${json(project)}},"created_at":"2026-09-01T10:00:00Z"}""",
        ).parent
        write(
            session.resolve("chat_history.jsonl"),
            listOf(
                """{"type":"system","content":"You are Grok"}""",
                """{"type":"user","content":[{"type":"text","text":"<user_info>OS: Windows</user_info>"}]}""",
                """{"type":"user","content":[{"type":"text","text":"<user_query>\nrefactor the parser\n</user_query>"}],"prompt_index":0}""",
                """{"type":"user","content":[{"type":"text","text":"<system-reminder>x</system-reminder>"}],"synthetic_reason":"reminder"}""",
                """{"type":"tool_result","tool_call_id":"t","content":"ok"}""",
                """{"type":"user","content":[{"type":"text","text":"thanks"}]}""",
            ).joinToString("\n"),
        )

        val raw = GrokProjectProvider(tempDirectory.resolve(".grok")).discover().single()

        assertEquals("2", raw.metadata["messageCount"])
        assertEquals("refactor the parser", raw.metadata["firstMessage"])
    }

    @Test
    fun `Kiro counts Prompt records of the transcript next to the session file`() {
        write(".kiro/sessions/cli/k1.json", """{"session_id":"k1","cwd":${json(project)},"title":"Kiro title"}""")
        write(
            ".kiro/sessions/cli/k1.jsonl",
            listOf(
                """{"version":"1","kind":"Prompt","data":{"message_id":"m","content":[{"kind":"text","data":"add logging"}],"meta":{"timestamp":1}}}""",
                """{"version":"1","kind":"AssistantMessage","data":{"message_id":"a","content":[{"kind":"text","data":"done"}]}}""",
                """{"version":"1","kind":"ToolResults","data":{"message_id":"t","content":[{"kind":"toolResult","data":{"status":"ok"}}]}}""",
                """{"version":"1","kind":"Prompt","data":{"message_id":"m2","content":[{"kind":"text","data":"now test it"}]}}""",
            ).joinToString("\n"),
        )

        val raw = KiroProjectProvider(tempDirectory.resolve(".kiro")).discover().single()

        assertEquals("2", raw.metadata["messageCount"])
        assertEquals("add logging", raw.metadata["firstMessage"])
    }

    @Test
    fun `Cline JSON sessions read the messages file and skip tool results`() {
        val messages = write(
            "cline-data/sessions/c1/c1.messages.json",
            """{"version":1,"messages":[""" +
                """{"role":"user","content":[{"type":"text","text":"<user_input mode=\"act\">build the feature</user_input>"}]},""" +
                """{"role":"assistant","content":[{"type":"text","text":"ok"}]},""" +
                """{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"done"}]},""" +
                """{"role":"user","content":[{"type":"text","text":"ship it"}]}]}""",
        )
        write(
            "cline-data/sessions/c1/c1.json",
            """{"session_id":"c1","cwd":${json(project)},"started_at":"2026-09-01T10:00:00Z","messages_path":${json(messages.toString())}}""",
        )

        val raw = ClineProjectProvider(tempDirectory.resolve("cline-data")) { emptyList() }.discover().single()

        assertEquals("2", raw.metadata["messageCount"])
        assertEquals("build the feature", raw.metadata["firstMessage"])
    }

    @Test
    fun `Cline database sessions follow their messages_path`() {
        val messages = write(
            "cline-data/sessions/c2/c2.messages.json",
            """{"messages":[{"role":"user","content":[{"type":"text","text":"from the database"}]}]}""",
        )
        Files.createDirectories(tempDirectory.resolve("cline-data/db"))
        write("cline-data/db/sessions.db", "fixture")
        val provider = ClineProjectProvider(tempDirectory.resolve("cline-data")) {
            listOf(ClineSessionRecord("c2", project, null, null, messagesPath = messages.toString()))
        }

        val raw = provider.discover().single { it.sessionId == "c2" }

        assertEquals("1", raw.metadata["messageCount"])
        assertEquals("from the database", raw.metadata["firstMessage"])
    }

    @Test
    fun `Cline sqlite rows carry the messages path when the column exists`() {
        val hex = { value: String -> value.toByteArray().joinToString("") { "%02X".format(it) } }
        val reader = ClineSqliteReader { _, _ ->
            listOf(hex("c3"), hex(project), hex("2026-09-01T10:00:00Z"), hex("2026-09-01T11:00:00Z"), hex("C:/m.json")).joinToString("\t")
        }

        val record = reader.readSessions(tempDirectory.resolve("sessions.db")).single()

        assertEquals("C:/m.json", record.messagesPath)
    }

    @Test
    fun `OpenCode merges the user message statistics query into the sessions`() {
        val hex = { value: String -> value.toByteArray().joinToString("") { "%02X".format(it) } }
        val reader = OpenCodeSqliteReader { args, _ ->
            val sql = args.last()
            if (sql.contains("json_extract")) {
                "${hex("o1")}\t3\t${hex("first  opencode\nprompt")}\n${hex("unknown")}\t9\t"
            } else {
                "${hex("o1")}\t${hex(project)}\t${hex("Title")}\t1767225600000\t1767229200000\n" +
                    "${hex("o2")}\t${hex(project)}\t${hex("Silent")}\t1767225600000\t1767229200000"
            }
        }

        val records = reader.readSessions(tempDirectory.resolve("opencode.db")).associateBy { it.id }

        assertEquals(3, records.getValue("o1").userMessageCount)
        assertEquals("first  opencode\nprompt", records.getValue("o1").firstMessage)
        assertEquals(0, records.getValue("o2").userMessageCount)
        assertNull(records.getValue("o2").firstMessage)
    }

    @Test
    fun `OpenCode keeps working without counts when the statistics query fails`() {
        val hex = { value: String -> value.toByteArray().joinToString("") { "%02X".format(it) } }
        val reader = OpenCodeSqliteReader { args, _ ->
            if (args.last().contains("json_extract")) null else "${hex("o1")}\t${hex(project)}\t${hex("T")}\t1\t2"
        }

        val record = reader.readSessions(tempDirectory.resolve("opencode.db")).single()

        assertNull(record.userMessageCount)
    }

    @Test
    fun `Qwen counts user records and reads their text parts`() {
        write(
            ".qwen/projects/p/chats/q1.jsonl",
            listOf(
                """{"sessionId":"q1","cwd":${json(project)},"timestamp":"2026-09-01T10:00:00Z","type":"system","subtype":"x"}""",
                """{"sessionId":"q1","cwd":${json(project)},"timestamp":"2026-09-01T10:00:01Z","type":"user","message":{"role":"user","parts":[{"text":"explain qwen"}]}}""",
                """{"sessionId":"q1","cwd":${json(project)},"timestamp":"2026-09-01T10:00:02Z","type":"assistant","message":{"role":"model","parts":[{"text":"sure","thought":false}]}}""",
                """{"sessionId":"q1","cwd":${json(project)},"timestamp":"2026-09-01T10:00:03Z","type":"tool_result","message":{"role":"user","parts":[{"functionResponse":{"id":"f"}}]}}""",
            ).joinToString("\n"),
        )

        val raw = QwenProjectProvider(tempDirectory.resolve(".qwen")).discover().single()

        assertEquals("1", raw.metadata["messageCount"])
        assertEquals("explain qwen", raw.metadata["firstMessage"])
    }

    @Test
    fun `a Qwen runtime file without a recorded chat is an empty session, one with a chat keeps the chat's count`() {
        val runtime = { id: String -> """{"schema_version":1,"session_id":"$id","work_dir":${json(project)},"started_at":1788269956.7}""" }
        write(".qwen/projects/p/chats/empty.runtime.json", runtime("empty"))
        write(".qwen/projects/p/chats/busy.runtime.json", runtime("busy"))
        write(
            ".qwen/projects/p/chats/busy.jsonl",
            """{"sessionId":"busy","cwd":${json(project)},"timestamp":"2026-09-01T10:00:01Z","type":"user","message":{"role":"user","parts":[{"text":"hello qwen"}]}}""",
        )

        val sessions = QwenProjectProvider(tempDirectory.resolve(".qwen")).discover().associateBy { it.sessionId }

        assertEquals("0", sessions.getValue("empty").metadata["messageCount"])
        assertEquals("1", sessions.getValue("busy").metadata["messageCount"])
        assertEquals("hello qwen", sessions.getValue("busy").metadata["firstMessage"])
    }

    @Test
    fun `Antigravity counts USER_INPUT steps and unwraps the user request`() {
        write(
            "antigravity-data/brain/conv-1/.system_generated/logs/transcript.jsonl",
            listOf(
                """{"step_index":0,"source":"SYSTEM","type":"SYSTEM_MESSAGE","created_at":"2026-09-01T10:00:00Z","content":"sys","cwd":${json(project)}}""",
                """{"step_index":1,"source":"USER_EXPLICIT","type":"USER_INPUT","created_at":"2026-09-01T10:00:01Z","content":"<USER_REQUEST>\nfix the tests\n</USER_REQUEST>\n<ADDITIONAL_METADATA>x</ADDITIONAL_METADATA>"}""",
                """{"step_index":2,"source":"MODEL","type":"PLANNER_RESPONSE","created_at":"2026-09-01T10:00:02Z","content":"ok"}""",
            ).joinToString("\n"),
        )

        val raw = AntigravityProjectProvider(tempDirectory.resolve("antigravity-data")).discover().single()

        assertEquals("1", raw.metadata["messageCount"])
        assertEquals("fix the tests", raw.metadata["firstMessage"])
    }

    private fun write(relativePath: String, contents: String): Path = write(tempDirectory.resolve(relativePath), contents)

    private fun write(file: Path, contents: String): Path {
        Files.createDirectories(file.parent)
        Files.writeString(file, contents)
        return file
    }
}
