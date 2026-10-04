package com.shutterstar.agenthub.projects.ui

import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.KeyStroke

/** Both mouse gestures and keyboard shortcuts use the same selected-row menu factory. */
internal object RowContextMenus {
    fun <T> install(list: JList<T>, menu: (T) -> JPopupMenu?) = install(
        list,
        selectedBounds = { list.selectedIndex.takeIf { it >= 0 }?.let { list.getCellBounds(it, it) } },
        selectAt = { event ->
            val index = list.locationToIndex(event.point)
            if (index >= 0 && list.getCellBounds(index, index)?.contains(event.point) == true) {
                list.selectedIndex = index
                true
            } else false
        },
        menu = { list.selectedValue?.let(menu) },
    )

    fun install(table: JTable, menu: (Int) -> JPopupMenu?) = install(
        table,
        selectedBounds = { table.selectedRow.takeIf { it >= 0 }?.let { table.getCellRect(it, 0, true) } },
        selectAt = { event ->
            val row = table.rowAtPoint(event.point)
            if (row >= 0 && table.columnAtPoint(event.point) >= 0) {
                table.setRowSelectionInterval(row, row)
                true
            } else false
        },
        menu = { table.selectedRow.takeIf { it >= 0 }?.let { menu(table.convertRowIndexToModel(it)) } },
    )

    fun install(
        component: JComponent,
        selectedBounds: () -> Rectangle?,
        selectAt: (MouseEvent) -> Boolean,
        menu: () -> JPopupMenu?,
    ) {
        val action = object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                val bounds = selectedBounds() ?: return
                if (component.isShowing) menu()?.show(component, bounds.x, bounds.y + bounds.height)
            }
        }
        component.getInputMap(JComponent.WHEN_FOCUSED).apply {
            put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, KeyEvent.SHIFT_DOWN_MASK), ACTION)
            put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), ACTION)
        }
        component.actionMap.put(ACTION, action)
        component.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = show(event)
            override fun mouseReleased(event: MouseEvent) = show(event)
            private fun show(event: MouseEvent) {
                if (event.isPopupTrigger && selectAt(event) && component.isShowing) {
                    menu()?.show(component, event.x, event.y)
                }
            }
        })
    }

    const val ACTION = "agenthub-row-context-menu"
}
