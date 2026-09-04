package com.shutterstar.agenthub.projects.ui

import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.JTable

/**
 * Tracks which row of a [JTable] the mouse is currently over and repaints the table when it
 * changes, since Swing's `JTable` has no built-in hover concept the way a tab strip does.
 */
internal class TableHoverTracker(private val table: JTable) {
    private var hoveredRow = -1

    init {
        table.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) = setHovered(table.rowAtPoint(event.point))
        })
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(event: MouseEvent) = setHovered(-1)
        })
    }

    fun isHovered(row: Int): Boolean = row == hoveredRow

    private fun setHovered(row: Int) {
        if (row == hoveredRow) return
        hoveredRow = row
        table.repaint()
    }
}
