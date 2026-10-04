package com.shutterstar.agenthub

import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.scale.JBUIScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.event.MouseEvent
import javax.swing.AbstractButton
import javax.swing.JTextField
import javax.swing.SwingUtilities

class SettingsOperationRegressionTest {
    @Test fun `right and middle clicks do not toggle an agent or run its action`() = SwingUtilities.invokeAndWait {
        val agent = CodingAgents.byId("codex")!!
        val row = AgentRow(agent, true)
        var actions = 0
        val context = AgentTableContext(
            { mapOf(agent.id to false) }, { emptySet() }, { emptySet() }, { emptySet() }, { 0 }, arrayOf("*"),
            { actions++ },
        )
        val agentTable = AgentTable(listOf(row), context)
        val table = agentTable.table
        table.setSize(900, 200)
        table.doLayout()
        fun click(column: Int, button: Int, count: Int) {
            val rect = if (column == 4) agentTable.actionButtonBounds(0)!! else table.getCellRect(0, column, false)
            val event = MouseEvent(table, MouseEvent.MOUSE_CLICKED, 0, 0, rect.x + 5, rect.y + 5, count, false, button)
            table.mouseListeners.forEach { it.mouseClicked(event) }
        }
        for (button in listOf(MouseEvent.BUTTON2, MouseEvent.BUTTON3)) {
            for (count in listOf(1, 2)) {
                click(1, button, count)
                click(4, button, count)
                click(5, button, count)
                click(6, button, count)
            }
        }
        assertTrue(row.enabled)
        assertEquals(0, actions)
        click(1, MouseEvent.BUTTON1, 1)
        click(4, MouseEvent.BUTTON1, 1)
        assertFalse(row.enabled)
        assertEquals(1, actions)
    }

    @Test fun `running operations remain disabled after settings is reopened`() = SwingUtilities.invokeAndWait {
        val first = AgentSettingsConfigurable(AgentSettingsState())
        val second = AgentSettingsConfigurable(AgentSettingsState())
        val setBusy = first.javaClass.getDeclaredMethod("setInProgress", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        try {
            setBusy.invoke(first, "codex", true)
            first.disposeUIResources()
            second.createComponent()
            assertTrue((second.javaClass.getDeclaredMethod("getInProgressAgentIds").apply { isAccessible = true }.invoke(second) as Set<*>).contains("codex"))
            assertTrue(field<javax.swing.Timer>(second, "spinnerTimer").isRunning)
            setBusy.invoke(first, "codex", false)
            assertFalse((second.javaClass.getDeclaredMethod("getInProgressAgentIds").apply { isAccessible = true }.invoke(second) as Set<*>).contains("codex"))
            assertFalse(field<javax.swing.Timer>(second, "spinnerTimer").isRunning)
        } finally {
            setBusy.invoke(first, "codex", false)
            first.disposeUIResources()
            second.disposeUIResources()
        }
    }

    @Test fun `invalid custom command prevents partial settings persistence`() = SwingUtilities.invokeAndWait {
        val settings = AgentSettingsState()
        val configurable = AgentSettingsConfigurable(settings)
        field<AbstractButton>(configurable, "customEnabledCheckbox").isSelected = true
        field<JTextField>(configurable, "customNameField").text = "Pending name"
        field<JTextField>(configurable, "customCommandField").text = "  "
        assertThrows(ConfigurationException::class.java) { configurable.apply() }
        assertFalse(settings.state.customAgentEnabled)
        assertEquals("", settings.state.customAgentName)
        configurable.disposeUIResources()
    }

    @Test fun `invalid URL prevents saving and labels expose their field targets`() = SwingUtilities.invokeAndWait {
        val settings = AgentSettingsState()
        val configurable = AgentSettingsConfigurable(settings)
        val content = configurable.createComponent()!!
        val url = field<JTextField>(configurable, "customUrlField")
        url.text = "javascript:alert(1)"
        field<JTextField>(configurable, "customNameField").text = "Draft"
        assertThrows(ConfigurationException::class.java) { configurable.apply() }
        assertEquals("", settings.state.customAgentName)
        assertTrue(field<javax.swing.JLabel>(configurable, "customValidationLabel").isVisible)
        assertTrue(com.shutterstar.agenthub.descendants(content as java.awt.Container).filterIsInstance<javax.swing.JLabel>().any { it.labelFor === url })
        url.text = "https://example.com/agent"
        configurable.apply()
        assertEquals("https://example.com/agent", settings.state.customAgentUrl)
        configurable.disposeUIResources()
    }

    @Test fun `confirmed WSL snapshot remains stable after global settings change`() {
        val original = WslSupport.settings
        try {
            val confirmed = WslSupport.Settings(true, "Confirmed-Distro")
            WslSupport.settings = WslSupport.Settings(true, "Other-Distro")
            assertEquals("Confirmed-Distro", WslSupport.wrapArgv("codex", confirmed)[2])
            assertTrue(WslSupport.wrapForTerminal("codex", confirmed).contains("Confirmed-Distro"))
            assertFalse(WslSupport.wrapForTerminal("codex", confirmed).contains("Other-Distro"))
            if (OsDetector.isWindows()) {
                assertEquals("Confirmed-Distro", TerminalCommandRunner.backgroundArgv("codex", confirmed)[2])
                assertEquals("powershell", TerminalCommandRunner.backgroundArgv("codex", WslSupport.Settings(false)).first())
            }
        } finally {
            WslSupport.settings = original
        }
    }

    @Test fun `command selection uses the confirmed environment`() {
        val agent = CodingAgents.all.first { it.installHintWindows.isNotBlank() && it.installHintWindows != it.installHint }
        assertEquals(agent.installHint, agent.installCommand(true))
        if (OsDetector.isWindows()) assertEquals(agent.installHintWindows, agent.installCommand(false))
        assertEquals(agent.uninstallHint, agent.uninstallCommand(true))
        assertEquals(agent.updateHint, agent.updateCommand(true))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(value: Any, name: String): T = value.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(value) as T

    companion object {
        @JvmStatic @BeforeAll fun scaling() { JBUIScale.setSystemScaleFactor(1.0f) }
    }
}
