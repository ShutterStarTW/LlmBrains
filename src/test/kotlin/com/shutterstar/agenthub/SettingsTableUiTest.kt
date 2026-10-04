package com.shutterstar.agenthub

import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import java.awt.Font
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

class SettingsTableUiTest {
    @Test fun `running actions reject repeated clicks and Enter until completion`() = SwingUtilities.invokeAndWait {
        val busy = mutableSetOf<String>()
        var operations = 0
        val agents = agentTable(busy) { agent -> busy.add(agent.id); operations++ }
        val table = agents.table
        table.setSize(900, 400)
        table.doLayout()
        fun click() {
            val rect = agents.actionButtonBounds(0)!!
            table.mouseListeners.forEach { it.mouseClicked(MouseEvent(table, MouseEvent.MOUSE_CLICKED, 0, 0,
                rect.x + rect.width / 2, rect.y + rect.height / 2, 1, false, MouseEvent.BUTTON1)) }
        }
        click()
        click()
        table.setRowSelectionInterval(0, 0)
        table.actionMap.get("agenthub-trigger-action").actionPerformed(java.awt.event.ActionEvent(table, 0, "Enter"))
        assertEquals(1, operations)
        assertFalse(actionButton(table.prepareRenderer(table.getCellRenderer(0, 4), 0, 4)).isEnabled)
        busy.clear()
        click()
        assertEquals(2, operations)
    }

    @Test fun `action renderer exposes focus rollover disabled progress and accessible operation`() = SwingUtilities.invokeAndWait {
        val inProgress = mutableSetOf<String>()
        val table = agentTable(inProgress).table
        table.setSize(900, 400)
        table.doLayout()
        val rectangle = agentTableBounds(table)
        val hover = MouseEvent(table, MouseEvent.MOUSE_MOVED, 0, 0, rectangle.x + rectangle.width / 2, rectangle.y + rectangle.height / 2, 0, false)
        table.mouseMotionListeners.forEach { it.mouseMoved(hover) }
        val renderer = table.getCellRenderer(0, 4)
        val button = actionButton(renderer.getTableCellRendererComponent(table, "Install", true, true, 0, 4))
        assertTrue(button.isEnabled)
        assertTrue(button.model.isRollover)
        assertTrue(button.hasFocus())
        assertEquals("Install Codex CLI", button.accessibleContext.accessibleName)
        inProgress.add("codex")
        val progress = actionButton(renderer.getTableCellRendererComponent(table, "Install", false, false, 0, 4))
        assertFalse(progress.isEnabled)
        assertFalse(progress.model.isRollover)
        assertFalse(progress.hasFocus())
        assertEquals("Operation in progress", progress.accessibleContext.accessibleName)
        assertTrue(progress.text.startsWith("* "))
        assertTrue(progress.preferredSize.width <= table.columnModel.getColumn(4).width)
    }

    @DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "needs a display; the CI runner is headless")
    @Test fun `clicking an action preserves the existing row selection`() = SwingUtilities.invokeAndWait {
        var actionCount = 0
        val table = agentTable(onAction = { actionCount++ }).table
        table.setSize(900, 400)
        table.doLayout()
        table.setRowSelectionInterval(1, 1)
        val bounds = agentTableBounds(table)
        for (id in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            table.dispatchEvent(MouseEvent(table, id, System.currentTimeMillis(), 0,
                bounds.x + 4, bounds.y + 4, 1, false, MouseEvent.BUTTON1))
        }
        assertEquals(1, table.selectedRow)
        assertEquals(1, actionCount)
    }

    @Test fun `hover and click outside the action button do not activate it`() = SwingUtilities.invokeAndWait {
        var actionCount = 0
        val agentTable = agentTable(onAction = { actionCount++ })
        val table = agentTable.table
        table.setSize(900, 400)
        table.doLayout()
        val cell = table.getCellRect(0, 4, false)
        val button = agentTable.actionButtonBounds(0)!!
        assertTrue(button.width < cell.width || button.height < cell.height)
        val x = if (button.x > cell.x) cell.x else cell.x + cell.width - 1
        val y = if (button.x > cell.x) button.y + button.height / 2 else cell.y
        table.mouseMotionListeners.forEach {
            it.mouseMoved(MouseEvent(table, MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false))
        }
        assertFalse(actionButton(table.prepareRenderer(table.getCellRenderer(0, 4), 0, 4)).model.isRollover)
        table.mouseListeners.forEach {
            it.mouseClicked(MouseEvent(table, MouseEvent.MOUSE_CLICKED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1))
        }
        assertEquals(0, actionCount)
    }

    @Test fun `hover colour applied after rendering does not leak into other rows`() = SwingUtilities.invokeAndWait {
        val table = agentTable().table
        listOf(1, 2, 3, 5, 6).forEach { col ->
            val first = table.prepareRenderer(table.getCellRenderer(0, col), 0, col)
            first.background = java.awt.Color.MAGENTA // what JBTable does for the hovered row
            val second = table.prepareRenderer(table.getCellRenderer(1, col), 1, col)
            assertEquals(table.background, second.background, "column $col")
        }
    }

    private fun actionButton(component: java.awt.Component): JButton = (component as JPanel).getComponent(0) as JButton

    private fun agentTableBounds(table: javax.swing.JTable): java.awt.Rectangle {
        val cell = table.getCellRect(0, 4, false)
        val button = actionButton(table.prepareRenderer(table.getCellRenderer(0, 4), 0, 4))
        return java.awt.Rectangle(cell.x + (cell.width - button.preferredSize.width) / 2,
            cell.y + (cell.height - button.preferredSize.height) / 2,
            button.preferredSize.width, button.preferredSize.height)
    }

    private fun agentTable(inProgress: Set<String> = emptySet(), onAction: (CodingAgent) -> Unit = {}): AgentTable {
        val agents = listOf("claude", "codex").map { CodingAgents.byId(it)!! }
        val context = AgentTableContext(
            { agents.associate { it.id to false } }, { emptySet() }, { emptySet() }, { inProgress }, { 0 }, arrayOf("*"), onAction,
        )
        // Codex first makes the assertions independent of registry order.
        return AgentTable(agents.reversed().map { AgentRow(it, true) }, context)
    }

    private fun screenshot(table: javax.swing.JTable, scale: Float) {
        val directory = System.getProperty("agenthub.ui.screenshot.dir") ?: return
        val scroll = JScrollPane(table)
        scroll.setSize(JBUI.scale(730), JBUI.scale(180))
        repeat(2) { scroll.doLayout(); scroll.viewport.doLayout(); table.doLayout() }
        val image = BufferedImage(scroll.width, scroll.height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try { scroll.printAll(graphics) } finally { graphics.dispose() }
        val target = Path.of(directory)
        Files.createDirectories(target)
        ImageIO.write(image, "png", target.resolve("settings-table-$scale.png").toFile())
    }
    companion object { @JvmStatic @BeforeAll fun scaling() { JBUIScale.setSystemScaleFactor(1.0f) } }
}
