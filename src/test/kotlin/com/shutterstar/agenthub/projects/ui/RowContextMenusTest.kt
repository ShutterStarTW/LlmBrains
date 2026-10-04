package com.shutterstar.agenthub.projects.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.RowFilter
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

class RowContextMenusTest {
    @Test fun `mouse and both keyboard shortcuts use the same selected list row`() = SwingUtilities.invokeAndWait {
        val list = object : JList<String>(arrayOf("first", "second")) { override fun isShowing() = true }
        list.fixedCellHeight = 30
        list.setSize(200, 100)
        val invoked = mutableListOf<String>()
        val popup = RecordingPopup()
        RowContextMenus.install(list) { invoked += it; popup }
        list.selectedIndex = 1
        for (key in listOf(KeyStroke.getKeyStroke(KeyEvent.VK_F10, KeyEvent.SHIFT_DOWN_MASK), KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0))) {
            val name = list.inputMap.get(key)
            assertNotNull(name)
            list.actionMap.get(name).actionPerformed(ActionEvent(list, 0, "menu"))
        }
        assertEquals(listOf("second", "second"), invoked)
        assertEquals(60, popup.lastY)
        val event = MouseEvent(list, MouseEvent.MOUSE_RELEASED, 0, 0, 10, 10, 1, true, MouseEvent.BUTTON3)
        list.mouseListeners.forEach { it.mouseReleased(event) }
        assertEquals(0, list.selectedIndex)
        assertEquals("first", invoked.last())
        val outside = MouseEvent(list, MouseEvent.MOUSE_RELEASED, 0, 0, 10, 95, 1, true, MouseEvent.BUTTON3)
        list.mouseListeners.forEach { it.mouseReleased(outside) }
        assertEquals(3, invoked.size)
        list.clearSelection()
        list.actionMap.get(RowContextMenus.ACTION).actionPerformed(ActionEvent(list, 0, "menu"))
        assertEquals(3, invoked.size)
    }

    @Test fun `table menus translate a filtered view row to its model row`() = SwingUtilities.invokeAndWait {
        val model = DefaultTableModel(arrayOf(arrayOf<Any>("hidden"), arrayOf<Any>("visible")), arrayOf("Name"))
        val table = object : JTable(model) { override fun isShowing() = true }
        table.rowSorter = TableRowSorter(model).apply {
            rowFilter = object : RowFilter<DefaultTableModel, Int>() {
                override fun include(entry: Entry<out DefaultTableModel, out Int>) = entry.identifier == 1
            }
        }
        var selected = -1
        RowContextMenus.install(table) { selected = it; RecordingPopup() }
        table.setRowSelectionInterval(0, 0)
        table.actionMap.get(RowContextMenus.ACTION).actionPerformed(ActionEvent(table, 0, "menu"))
        assertEquals(1, selected)
    }

    private class RecordingPopup : JPopupMenu() {
        var lastY = -1
        override fun show(invoker: Component, x: Int, y: Int) { lastY = y }
    }
}