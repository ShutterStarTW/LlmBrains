package com.shutterstar.agenthub

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Windows and every WSL distribution keep their own agent/tool selection and detection results. */
class AgentSettingsStateProfileTest {
    @BeforeEach
    fun noRealWsl() {
        AgentRuntime.resetForTests()
        AgentRuntime.wslLookup = { null }
    }

    @AfterEach
    fun restore() {
        AgentRuntime.resetForTests()
        WslSupport.settings = WslSupport.Settings()
    }


    @Test
    fun `should discard background results after a switch even when the original profile is selected again`() {
        val settings = AgentSettingsState()
        val host = settings.executionSnapshot()
        settings.setWslMode(true, "Ubuntu")
        val wsl = settings.executionSnapshot()
        settings.setWslMode(false, "")

        assertFalse(settings.applyIfCurrent(host) { settings.saveDetectionResults(mapOf("claude" to true)) })
        assertFalse(settings.applyIfCurrent(wsl) { settings.saveOutdatedAgents(listOf("codex")) })
        assertFalse(settings.isInstallationKnown())
        assertTrue(settings.getOutdatedAgentIds().isEmpty())
        assertTrue(settings.applyIfCurrent(settings.executionSnapshot()) {
            settings.saveDetectionResults(mapOf("codex" to true))
        })
        assertEquals(setOf("codex"), settings.visibleAgentIds())
    }

    @Test
    fun `should notify listeners only after both the profile and execution runtime have changed`() {
        val settings = AgentSettingsState()
        val observed = mutableListOf<Pair<String, WslSupport.Settings>>()
        settings.addDetectionListener { observed += settings.runtimeKey() to WslSupport.settings }

        settings.setWslMode(true, "Ubuntu")
        settings.setWslMode(true, "Debian")
        settings.setWslMode(false, "")

        assertEquals(
            listOf(
                "wsl:ubuntu" to WslSupport.Settings(true, "Ubuntu"),
                "wsl:debian" to WslSupport.Settings(true, "Debian"),
                "host" to WslSupport.Settings(),
            ),
            observed,
        )
    }


    @Test
    fun `should build command arguments from the captured runtime rather than the current one`() {
        WslSupport.settings = WslSupport.Settings(false, "")
        val captured = WslSupport.Settings(true, "Ubuntu")
        val arguments = AgentDetector.shellArgv("claude --version", captured).toList()
        if (OsDetector.isWindows()) {
            assertEquals("wsl.exe", arguments.first())
            assertEquals("Ubuntu", arguments[arguments.indexOf("-d") + 1])
        } else {
            assertEquals("bash", arguments.first())
        }
        assertFalse(WslSupport.settings.useWsl)
    }

    @Test
    fun `the environment is named by mode and distribution`() {
        assertEquals("host", AgentSettingsState.runtimeKey(false, "Ubuntu"))
        assertEquals("wsl:ubuntu", AgentSettingsState.runtimeKey(true, " Ubuntu "))
        assertEquals("wsl:default", AgentSettingsState.runtimeKey(true, ""))
        assertEquals("Windows (native)", AgentSettingsState.runtimeLabel(false, "Ubuntu"))
        assertEquals("WSL (Ubuntu)", AgentSettingsState.runtimeLabel(true, "Ubuntu"))
        assertEquals("WSL (default distribution)", AgentSettingsState.runtimeLabel(true, ""))
    }

    @Test
    fun `settings saved before the profiles existed belong to the environment selected now`() {
        val settings = AgentSettingsState()
        val legacy = AgentSettingsState.State(inactiveAgentIds = mutableListOf("cursor"), useWsl = true, wslDistro = "Ubuntu")

        settings.loadState(legacy)

        assertEquals("wsl:ubuntu", settings.runtimeKey())
        assertFalse(settings.isAgentActive("cursor"), "the legacy selection is kept, not reset")
    }

    @Test
    fun `switching the environment swaps the selection and the detection results and back again`() {
        val settings = AgentSettingsState()
        settings.setAgentActive("cursor", false)
        settings.setCompanionActive(CompanionTools.all.first().id, true)
        settings.saveDetectionResults(mapOf("claude" to true, "codex" to false))

        settings.setWslMode(true, "Ubuntu")

        assertEquals("wsl:ubuntu", settings.runtimeKey())
        assertTrue(settings.isAgentActive("cursor"), "a new environment starts with its own, empty selection")
        assertFalse(settings.isCompanionActive(CompanionTools.all.first().id))
        assertFalse(settings.isInstallationKnown(), "nothing is known about the distribution yet")

        settings.saveDetectionResults(mapOf("codex" to true))
        settings.setAgentActive("claude", false)
        assertEquals(setOf("codex"), settings.visibleAgentIds())

        settings.setWslMode(false, "")

        assertEquals("host", settings.runtimeKey())
        assertFalse(settings.isAgentActive("cursor"))
        assertTrue(settings.isAgentActive("claude"))
        assertTrue(settings.isCompanionActive(CompanionTools.all.first().id))
        assertEquals(setOf("claude"), settings.visibleAgentIds())

        settings.setWslMode(true, "Ubuntu")

        assertFalse(settings.isAgentActive("claude"))
        assertEquals(setOf("codex"), settings.visibleAgentIds())
    }

    @Test
    fun `each distribution has its own profile`() {
        val settings = AgentSettingsState()
        settings.setWslMode(true, "Ubuntu")
        settings.setAgentActive("claude", false)

        settings.setWslMode(true, "Debian")

        assertTrue(settings.isAgentActive("claude"))
        settings.setWslMode(true, "ubuntu")
        assertFalse(settings.isAgentActive("claude"), "the name is not case sensitive")
    }

    @Test
    fun `an environment seen for the first time gets the default selection after its first detection`() {
        val settings = AgentSettingsState()
        val installedButNotDefault = CodingAgents.all.map { it.id }.first { it !in CodingAgents.defaultActiveIds }

        settings.setWslMode(true, "Ubuntu")
        settings.saveDetectionResults(mapOf(installedButNotDefault to true))

        val inactive = CodingAgents.all.map { it.id }.filterNot { settings.isAgentActive(it) }
        assertTrue(settings.isAgentActive(installedButNotDefault), "an installed agent is enabled")
        assertTrue(CodingAgents.defaultActiveIds.all { it !in inactive }, "the default set is enabled")
        assertTrue(inactive.isNotEmpty(), "the rest is not")
    }

    @Test
    fun `switching the environment announces a new installed set`() {
        val settings = AgentSettingsState()
        var notifications = 0
        val before = settings.detectionGeneration
        settings.addDetectionListener { notifications++ }

        settings.setWslMode(true, "Ubuntu")
        settings.setWslMode(true, "Ubuntu")

        assertEquals(1, notifications, "only a real switch is announced")
        assertTrue(settings.detectionGeneration > before)
    }
}
