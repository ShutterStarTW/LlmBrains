package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

/**
 * The behaviour every file-based [AgentProjectProvider] must share, written once. Each provider only
 * supplies how to lay a minimal valid session out on disk; provider-specific formats, fallbacks and
 * metadata stay in the per-provider tests. Cline and OpenCode are covered through their JSON storage;
 * their SQLite readers are tested separately.
 */
class ProjectProviderContractTest {
    interface Fixture {
        val agentId: String

        fun provider(home: Path): AgentProjectProvider

        /** Creates the (empty) storage root that makes the provider available. */
        fun createStorage(home: Path)

        /**
         * Writes one valid session and returns its source file. [slot] keeps two sessions that share an id
         * in different places; [updatedAt] is both the recorded activity and the file modification time.
         */
        fun write(home: Path, sessionId: String, project: Path, updatedAt: Instant, slot: Int): Path
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    fun `missing and empty storage return no sessions`(fixture: Fixture, @TempDir home: Path) {
        val provider = fixture.provider(home)

        assertFalse(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())

        fixture.createStorage(home)
        assertTrue(provider.isAvailable())
        assertTrue(provider.discover().isEmpty())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    fun `discovers multiple sessions and projects`(fixture: Fixture, @TempDir home: Path) {
        val first = home.resolve("work/first")
        val second = home.resolve("work/second")
        fixture.write(home, "one", first, AT, 1)
        fixture.write(home, "two", first, AT, 2)
        fixture.write(home, "three", second, AT, 3)

        val sessions = fixture.provider(home).discover()

        assertEquals(3, sessions.size)
        assertEquals(setOf(fixture.agentId), sessions.mapTo(mutableSetOf()) { it.agentId })
        assertEquals(2, sessions.count { it.rawProjectPath == first.toString() })
        assertEquals(1, sessions.count { it.rawProjectPath == second.toString() })
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    fun `duplicate session ids keep the newest source`(fixture: Fixture, @TempDir home: Path) {
        val oldProject = home.resolve("work/old")
        val newProject = home.resolve("work/new")
        fixture.write(home, "duplicate", oldProject, AT.minusSeconds(3 * DAY), 1)
        val newest = fixture.write(home, "duplicate", newProject, AT, 2)

        val session = fixture.provider(home).discover().single()

        assertEquals("duplicate", session.sessionId)
        assertEquals(newProject.toString(), session.rawProjectPath)
        assertEquals(newest.toAbsolutePath().normalize().toString(), session.sourcePath)
    }

    companion object {
        private val AT = Instant.parse("2026-08-26T10:00:00Z")
        private const val DAY = 86_400L

        private fun file(path: Path, contents: String, modifiedAt: Instant): Path {
            Files.createDirectories(path.parent)
            Files.writeString(path, contents)
            Files.setLastModifiedTime(path, FileTime.from(modifiedAt))
            return path
        }

        private fun fixture(
            agentId: String,
            provider: (Path) -> AgentProjectProvider,
            storage: (Path) -> Path,
            write: (home: Path, id: String, project: Path, at: Instant, slot: Int) -> Path,
        ) = object : Fixture {
            override val agentId = agentId
            override fun provider(home: Path) = provider(home)
            override fun createStorage(home: Path) {
                Files.createDirectories(storage(home))
            }
            override fun write(home: Path, sessionId: String, project: Path, updatedAt: Instant, slot: Int) =
                write(home, sessionId, project, updatedAt, slot)
            override fun toString() = agentId
        }

        private fun percentEncode(value: String): String = buildString {
            value.toByteArray(StandardCharsets.UTF_8).forEach { raw ->
                val unsigned = raw.toInt() and 0xFF
                val character = unsigned.toChar()
                if (character.isLetterOrDigit() || character == '.' || character == '-' || character == '_') {
                    append(character)
                } else {
                    append('%').append(unsigned.toString(16).uppercase().padStart(2, '0'))
                }
            }
        }

        @JvmStatic
        fun fixtures(): List<Fixture> = listOf(
            fixture("antigravity", { AntigravityProjectProvider(it.resolve("antigravity-data")) }, { it.resolve("antigravity-data/brain") }) { home, id, project, at, slot ->
                // Odd slots use the flat conversations/ layout, even slots the brain transcript, so a duplicate id spans both.
                val data = home.resolve("antigravity-data")
                if (slot % 2 == 1) {
                    val line = """{"sessionId":${json(id)},"workspace":${json(project.toString())},"created_at":${json(at.toString())}}"""
                    file(data.resolve("conversations/$id.jsonl"), line + "\n", at)
                } else {
                    val line = """{"step_index":0,"type":"USER_INPUT","created_at":${json(at.toString())},"cwd":${json(project.toString())}}"""
                    file(data.resolve("brain/$id/.system_generated/logs/transcript.jsonl"), line + "\n", at)
                }
            },
            fixture("cline", { ClineProjectProvider(it.resolve("cline-data")) }, { it.resolve("cline-data/sessions") }) { home, id, project, at, slot ->
                val session = """{"session_id":${json(id)},"cwd":${json(project.toString())},"updated_at":${json(at.toString())}}"""
                file(home.resolve("cline-data/sessions/key$slot/$id.json"), session, at)
            },
            fixture("claude", { ClaudeProjectProvider(it) }, { it.resolve(".claude/projects") }) { home, id, project, at, slot ->
                val line = """{"sessionId":${json(id)},"cwd":${json(project.toString())},"timestamp":${json(at.toString())},"type":"user"}"""
                file(home.resolve(".claude/projects/key$slot/$id-$slot.jsonl"), line + "\n", at)
            },
            fixture("codex", { CodexProjectProvider(it.resolve(".codex")) }, { it.resolve(".codex/sessions") }) { home, id, project, at, slot ->
                val line = """{"timestamp":${json(at.toString())},"type":"session_meta","payload":{"id":${json(id)},"timestamp":${json(at.toString())},"cwd":${json(project.toString())}}}"""
                file(home.resolve(".codex/sessions/2026/08/${10 + slot}/$id-$slot.jsonl"), line + "\n", at)
            },
            fixture("copilot", { CopilotProjectProvider(it) }, { it.resolve(".copilot/sessions") }) { home, id, project, at, slot ->
                val line = """{"id":${json(id)},"cwd":${json(project.toString())},"timestamp":${json(at.toString())},"type":"user"}"""
                file(home.resolve(".copilot/sessions/key$slot/$id-$slot.jsonl"), line + "\n", at)
            },
            fixture("cursor", { CursorProjectProvider(it.resolve(".cursor")) }, { it.resolve(".cursor/chats") }) { home, id, project, at, slot ->
                val meta = """{"schemaVersion":1,"createdAtMs":${at.toEpochMilli()},"hasConversation":true,"title":${json(id)},"updatedAtMs":${at.toEpochMilli()},"cwd":${json(project.toString())}}"""
                file(home.resolve(".cursor/chats/hash$slot/$id/meta.json"), meta, at)
            },
            fixture("freebuff", { FreebuffProjectProvider(it.resolve(".config/manicode")) }, { it.resolve(".config/manicode/projects") }) { home, id, project, at, slot ->
                val chat = home.resolve(".config/manicode/projects/p$slot/chats/$id")
                file(chat.resolve("run-state.json"), """{"sessionState":{"fileContext":{"projectRoot":${json(project.toString())}}}}""", at)
                file(chat.resolve("chat-messages.json"), """[{"id":"m","variant":"user","content":"Hi","blocks":[],"timestamp":"t"}]""", at)
                chat
            },
            fixture("grok", { GrokProjectProvider(it.resolve(".grok")) }, { it.resolve(".grok/sessions") }) { home, id, project, at, _ ->
                val summary = """{"info":{"id":${json(id)},"cwd":${json(project.toString())}},"created_at":${json(at.toString())},"last_active_at":${json(at.toString())}}"""
                file(home.resolve(".grok/sessions/${percentEncode(project.toString())}/$id/summary.json"), summary, at)
            },
            fixture("junie", { JunieProjectProvider(it.resolve(".junie")) }, { it.resolve(".junie/sessions") }) { home, id, project, at, slot ->
                val session = home.resolve(".junie/sessions/dir$slot")
                val summary = """{"sessionId":${json(id)},"createdAt":${at.toEpochMilli()},"updatedAt":${at.toEpochMilli()},"projectDir":${json(project.toString())}}"""
                file(session.resolve("summary.json"), summary, at)
                file(session.resolve("events.jsonl"), """{"kind":"UserPromptEvent","prompt":"Hi","presentablePrompt":"Hi"}""" + "\n", at)
                session
            },
            fixture("kiro", { KiroProjectProvider(it.resolve(".kiro")) }, { it.resolve(".kiro/sessions/cli") }) { home, id, project, at, slot ->
                val meta = """{"session_id":${json(id)},"cwd":${json(project.toString())},"updated_at":${json(at.toString())}}"""
                file(home.resolve(".kiro/sessions/cli/$id-$slot.json"), meta, at)
            },
            fixture("opencode", { OpenCodeProjectProvider(it.resolve("opencode-data")) }, { it.resolve("opencode-data/project") }) { home, id, project, at, slot ->
                val session = """{"id":${json(id)},"directory":${json(project.toString())},"time":{"created":${at.toEpochMilli()},"updated":${at.toEpochMilli()}}}"""
                file(home.resolve("opencode-data/project/key$slot/storage/session/key$slot/$id.json"), session, at)
            },
            fixture("qwen", { QwenProjectProvider(it.resolve(".qwen")) }, { it.resolve(".qwen/projects") }) { home, id, project, at, slot ->
                val line = """{"sessionId":${json(id)},"cwd":${json(project.toString())},"timestamp":${json(at.toString())},"type":"system"}"""
                file(home.resolve(".qwen/projects/key$slot/chats/$id-$slot.jsonl"), line + "\n", at)
            },
            fixture("vibe", { VibeProjectProvider(it.resolve(".vibe")) }, { it.resolve(".vibe/logs/session") }) { home, id, project, at, slot ->
                val session = home.resolve(".vibe/logs/session/session_2026082${slot}_100000_$slot")
                val meta = """{"session_id": ${json(id)}, "start_time": ${json(at.toString())}, "end_time": ${json(at.toString())}, "environment": {"working_directory": ${json(project.toString())}}}"""
                file(session.resolve("meta.json"), meta, at)
                session
            },
        )
    }
}
