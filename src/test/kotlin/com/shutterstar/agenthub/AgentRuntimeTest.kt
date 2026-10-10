package com.shutterstar.agenthub

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AgentRuntimeTest {
    private val share = "\\\\wsl.localhost\\Ubuntu"

    @BeforeEach
    fun wslMode() {
        AgentRuntime.resetForTests()
        WslSupport.settings = WslSupport.Settings(useWsl = true, distro = "Ubuntu")
        AgentRuntime.wslActive = { true }
        AgentRuntime.separator = '\\'
        AgentRuntime.wslLookup = { AgentRuntime.WslEnvironment("Ubuntu", "/home/me", share) }
    }

    @AfterEach
    fun restore() {
        AgentRuntime.resetForTests()
        WslSupport.settings = WslSupport.Settings()
    }

    @Test
    fun `the home is the user's own outside WSL mode`() {
        AgentRuntime.wslActive = { false }

        assertEquals(AgentRuntime.hostHome(), AgentRuntime.userHome())
        assertEquals("host", AgentRuntime.scopeKey())
        assertFalse(AgentRuntime.isWsl())
        assertEquals("/home/me/project", AgentRuntime.toHostPath("/home/me/project"))
        assertNull(AgentRuntime.toLinuxPath("C:\\Users\\me"))
    }

    @Test
    fun `in WSL mode the home is the distro's home on the share`() {
        assertEquals("\\\\wsl.localhost\\Ubuntu\\home\\me", AgentRuntime.userHome().toString())
        assertEquals("/home/me", AgentRuntime.linuxHome())
        assertFalse(AgentRuntime.isWindowsRuntime())
    }

    @Test
    fun `the shared AgentHub store stays on the Windows home in WSL mode`() {
        val resolved = com.shutterstar.agenthub.storage.AgentHubHome.resolvePath(environmentValue = null, propertyValue = null)

        assertEquals(AgentRuntime.hostHome().resolve(".agenthub").toAbsolutePath().normalize(), resolved)
    }

    @Test
    fun `a failed lookup never falls back to the Windows home and is retried after a pause`() {
        var now = 0L
        AgentRuntime.nanoTime = { now }
        var found: AgentRuntime.WslEnvironment? = null
        val lookups = AtomicInteger()
        AgentRuntime.wslLookup = { lookups.incrementAndGet(); found }

        val unavailable = AgentRuntime.userHome().toString()
        assertTrue(unavailable.contains("unavailable"), unavailable)
        assertTrue(AgentRuntime.userHome().toString() == unavailable)
        assertEquals(1, lookups.get(), "a recent failure is not looked up again")

        found = AgentRuntime.WslEnvironment("Ubuntu", "/home/me", share)
        now += TimeUnit.SECONDS.toNanos(31)

        assertEquals("\\\\wsl.localhost\\Ubuntu\\home\\me", AgentRuntime.userHome().toString())
        assertEquals(2, lookups.get())
    }

    @Test
    fun `Linux paths of the distro are reached over the share and mnt paths become drive paths`() {
        assertEquals("\\\\wsl.localhost\\Ubuntu\\home\\me\\proj", AgentRuntime.toHostPath("/home/me/proj"))
        assertEquals("C:\\Users\\me\\proj", AgentRuntime.toHostPath("/mnt/c/Users/me/proj"))
        assertEquals("D:\\", AgentRuntime.toHostPath("/mnt/d"))
        assertEquals("C:\\Windows", AgentRuntime.toHostPath("C:\\Windows"))
        assertNull(AgentRuntime.toHostPath("relative/path"))
        assertNull(AgentRuntime.toHostPath("  "))
    }


    @Test
    fun `should retain UNC aliases of the selected distro and reject other shares`() {
        assertEquals(share + "\\home\\me\\proj", AgentRuntime.toHostPath(share + "\\home\\me\\proj"))
        assertEquals(share + "\\home\\me\\proj", AgentRuntime.toHostPath("//wsl.localhost/Ubuntu/home/me/proj"))
        assertEquals(share + "\\home\\me\\proj", AgentRuntime.toHostPath("\\\\wsl$\\Ubuntu\\home\\me\\proj"))
        assertNull(AgentRuntime.toHostPath("\\\\wsl.localhost\\Debian\\home\\me"))
        assertNull(AgentRuntime.toHostPath("//wsl.localhostile/Ubuntu/home/me"))
        assertEquals("K:\\ide" to "/home/me/proj", AgentRuntime.terminalDirectories("//wsl.localhost/Ubuntu/home/me/proj", "K:\\ide"))
    }

    @Test
    fun `share paths and drive paths go back to Linux paths`() {
        assertEquals("/home/me/proj", AgentRuntime.toLinuxPath("\\\\wsl.localhost\\Ubuntu\\home\\me\\proj"))
        assertEquals("/home/me/proj", AgentRuntime.toLinuxPath("\\\\wsl$\\Ubuntu\\home\\me\\proj"))
        assertEquals("/", AgentRuntime.toLinuxPath("\\\\wsl.localhost\\Ubuntu"))
        assertEquals("/mnt/c/Users/me", AgentRuntime.toLinuxPath("C:\\Users\\me"))
        assertEquals("/mnt/d", AgentRuntime.toLinuxPath("D:\\"))
        assertNull(AgentRuntime.toLinuxPath("\\\\wsl.localhost\\Debian\\home\\me"), "another distro")
        assertNull(AgentRuntime.toLinuxPath("\\\\wsl.localhostile\\Ubuntu\\x"))
    }

    @Test
    fun `a terminal for a project in the distro starts in the IDE project and changes directory through wsl`() {
        assertEquals(
            "K:\\ide-project" to "/home/me/proj",
            AgentRuntime.terminalDirectories("/home/me/proj", "K:\\ide-project"),
        )
        assertEquals(
            "K:\\ide-project" to "/home/me/proj",
            AgentRuntime.terminalDirectories("\\\\wsl.localhost\\Ubuntu\\home\\me\\proj", "K:\\ide-project"),
        )
        assertEquals("C:\\work" to null, AgentRuntime.terminalDirectories("C:\\work", "K:\\ide-project"))
        assertEquals("K:\\ide-project" to null, AgentRuntime.terminalDirectories(null, "K:\\ide-project"))
    }

    @Test
    fun `scoped values are rebuilt when the runtime changes and only then`() {
        val builds = AtomicInteger()
        val scoped = AgentRuntime.scoped { builds.incrementAndGet() }

        assertEquals(1, scoped.get())
        assertEquals(1, scoped.get(), "same runtime: same value")

        AgentRuntime.wslLookup = { AgentRuntime.WslEnvironment("Ubuntu", "/home/other", share) }
        AgentRuntime.resetCache()
        assertEquals(2, scoped.get(), "another home: rebuilt")

        AgentRuntime.wslActive = { false }
        assertEquals(3, scoped.get(), "back on the host: rebuilt")
        assertEquals(3, scoped.get())
    }
}
