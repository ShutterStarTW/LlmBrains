package com.shutterstar.agenthub.projects.launch

import com.shutterstar.agenthub.LaunchFlags
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeResumeCommandsTest {
    @Test
    fun `builds the verified resume command for each supported agent`() {
        val id = "0b1f7a2e-3c4d-4e5f-8a9b-0c1d2e3f4a5b"
        assertEquals("claude --resume $id", NativeResumeCommands.command("claude", id))
        assertEquals("codex resume $id", NativeResumeCommands.command("codex", id))
        assertEquals("opencode --session $id", NativeResumeCommands.command("opencode", id))
        assertEquals("kilo --session $id", NativeResumeCommands.command("kilo", id))
        assertEquals("omp --resume $id", NativeResumeCommands.command("omp", id))
        assertEquals("kimi --session $id", NativeResumeCommands.command("kimi", id))
        assertEquals("cline --id $id", NativeResumeCommands.command("cline", id))
        assertEquals("agy --conversation $id", NativeResumeCommands.command("antigravity", id))
        assertEquals("copilot --resume $id", NativeResumeCommands.command("copilot", id))
        assertEquals("cursor-agent --resume $id", NativeResumeCommands.command("cursor", id))
        assertEquals("grok --resume $id", NativeResumeCommands.command("grok", id))
        assertEquals("kiro-cli chat --resume-id $id", NativeResumeCommands.command("kiro", id))
        assertEquals("qwen --resume $id", NativeResumeCommands.command("qwen", id))
        assertEquals("freebuff --continue $id", NativeResumeCommands.command("freebuff", id))
        assertEquals("junie --resume --session-id=$id", NativeResumeCommands.command("junie", id))
        assertEquals("mimo --session $id", NativeResumeCommands.command("mimo", id))
        assertEquals("vibe --resume $id", NativeResumeCommands.command("vibe", id))
    }

    @Test
    fun `says why a session cannot be resumed`() {
        val id = "0b1f7a2e-3c4d-4e5f-8a9b-0c1d2e3f4a5b"
        assertNull(NativeResumeCommands.unavailableReason("claude", id))
        assertEquals(
            "AgentHub does not know a native resume command for this agent yet",
            NativeResumeCommands.unavailableReason("zzz", id),
        )
        assertEquals("This session has no resume ID recorded", NativeResumeCommands.unavailableReason("claude", null))
        assertEquals("This session has no resume ID recorded", NativeResumeCommands.unavailableReason("claude", "  "))
        assertTrue(NativeResumeCommands.unavailableReason("claude", "abc; rm -rf ~")!!.contains("not in a form"))
    }

    @Test
    fun `unsupported agents have no resume command`() {
        assertFalse(NativeResumeCommands.supports("zzz"))
        assertNull(NativeResumeCommands.command("zzz", "abc"))
        assertTrue(NativeResumeCommands.supports("claude"))
    }

    @Test
    fun `refuses blank or shell-unsafe session ids instead of quoting them`() {
        assertNull(NativeResumeCommands.command("claude", null))
        assertNull(NativeResumeCommands.command("claude", "   "))
        assertNull(NativeResumeCommands.command("claude", "abc; rm -rf ~"))
        assertNull(NativeResumeCommands.command("codex", "id with spaces"))
        assertNull(NativeResumeCommands.command("opencode", "\$(evil)"))
        assertEquals("codex resume ses_01ABC", NativeResumeCommands.command("codex", " ses_01ABC "))
    }

    @Test
    fun `refuses a session id that a CLI could read as an option`() {
        assertNull(NativeResumeCommands.command("claude", "--dangerously-skip-permissions"))
        assertNull(NativeResumeCommands.command("vibe", "-c"))
        assertNotNull(NativeResumeCommands.unavailableReason("claude", "--dangerously-skip-permissions"))
        assertEquals("claude --resume 1-2", NativeResumeCommands.command("claude", "1-2"))
    }

    @Test
    fun `adds only the launch flags the installed CLI lists in its help`() {
        val probes = mutableListOf<String>()
        val modern = { probe: String ->
            probes += probe
            "Usage: codex resume [OPTIONS]\n      --no-alt-screen\n      --no-daemon\n"
        }
        val legacy = { _: String -> "Usage: codex resume [OPTIONS]\n      --no-alt-screen\n" }
        LaunchFlags.clearCache()
        assertEquals("codex resume --no-daemon --no-alt-screen ses_1", NativeResumeCommands.command("codex", "ses_1", modern))
        assertEquals(listOf("codex resume --help"), probes)
        LaunchFlags.clearCache()
        assertEquals("codex resume --no-alt-screen ses_1", NativeResumeCommands.command("codex", "ses_1", legacy))
        LaunchFlags.clearCache()
        assertEquals("claude --resume ses_1", NativeResumeCommands.command("claude", "ses_1") { "" })
    }
}
