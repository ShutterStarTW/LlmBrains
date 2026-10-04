package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LaunchFlagsTest {
    @BeforeEach
    fun reset() = LaunchFlags.clearCache()

    @Test
    fun `includes only flags listed in the help output`() {
        val help = { _: String -> "Options:\n      --no-alt-screen  Disable alternate screen\n" }
        assertEquals(
            "codex --no-alt-screen",
            LaunchFlags.build("codex", listOf("--no-daemon", "--no-alt-screen"), help = help),
        )
    }

    @Test
    fun `failed or empty probe yields the bare command`() {
        assertEquals("codex", LaunchFlags.build("codex", listOf("--no-daemon"), help = { "" }))
        assertEquals("codex", LaunchFlags.build("codex", listOf("--no-daemon"), help = { error("boom") }))
    }

    @Test
    fun `no flags means no probe at all`() {
        var probed = false
        assertEquals("claude", LaunchFlags.build("claude", emptyList()) { probed = true; "" })
        assertFalse(probed)
    }

    @Test
    fun `probes the subcommand help and caches successful probes`() {
        val probes = mutableListOf<String>()
        val help = { probe: String -> probes += probe; "--no-daemon" }
        assertEquals("codex resume --no-daemon", LaunchFlags.build("codex", listOf("--no-daemon"), "resume", help))
        LaunchFlags.build("codex", listOf("--no-daemon"), "resume", help)
        assertEquals(listOf("codex resume --help"), probes)
    }

    @Test
    fun `failed probes are not cached`() {
        var calls = 0
        val help = { _: String -> calls++; "" }
        LaunchFlags.build("codex", listOf("--no-daemon"), help = help)
        LaunchFlags.build("codex", listOf("--no-daemon"), help = help)
        assertEquals(2, calls)
    }

    @Test
    fun `matches whole option tokens only`() {
        assertTrue(LaunchFlags.mentions("  --no-daemon  skip", "--no-daemon"))
        assertFalse(LaunchFlags.mentions("--no-daemon-mode", "--no-daemon"))
        assertFalse(LaunchFlags.mentions("x--no-daemon", "--no-daemon"))
        assertFalse(LaunchFlags.mentions("", "--no-daemon"))
    }
}
