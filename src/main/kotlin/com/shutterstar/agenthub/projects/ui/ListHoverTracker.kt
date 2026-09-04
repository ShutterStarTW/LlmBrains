package com.shutterstar.agenthub.projects.ui

import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.JList

/**
 * Tracks which row of a [JList] the mouse is currently over and repaints the list when it
 * changes, since Swing's `JList` has no built-in hover concept the way a tab strip does.
 */
internal class ListHoverTracker(private val list: JList<*>) {
    private var hoveredIndex = -1

    init {
        list.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                val index = list.locationToIndex(event.point)
                val bounds = index.takeIf { it >= 0 }?.let { list.getCellBounds(it, it) }
                setHovered(if (bounds != null && bounds.contains(event.point)) index else -1)
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(event: MouseEvent) = setHovered(-1)
        })
    }

    fun isHovered(index: Int): Boolean = index == hoveredIndex

    private fun setHovered(index: Int) {
        if (index == hoveredIndex) return
        hoveredIndex = index
        list.repaint()
    }
}
