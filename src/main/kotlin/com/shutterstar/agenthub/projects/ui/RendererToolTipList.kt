package com.shutterstar.agenthub.projects.ui

import com.intellij.ui.components.JBList
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.ListModel
import javax.swing.SwingUtilities

/** Resolves a list renderer's child tooltip at the mouse position, including nested icon labels. */
internal open class RendererToolTipList<T>(model: ListModel<T>) : JBList<T>(model) {
    /**
     * Rows wrap their info lines, so their height depends on the list width - but the list caches
     * cell heights. Re-assigning the fixed cell width (a property change) makes it measure the rows
     * again. Done synchronously here rather than from a ComponentListener, whose event is queued.
     */
    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        if (width > 0 && fixedCellWidth != width) fixedCellWidth = width
    }

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getToolTipText(event: MouseEvent): String? {
        val index = locationToIndex(event.point)
        if (index < 0) return null
        val bounds = getCellBounds(index, index) ?: return null
        if (!bounds.contains(event.point)) return null
        val renderer = cellRenderer.getListCellRendererComponent(
            this,
            model.getElementAt(index),
            index,
            isSelectedIndex(index),
            false,
        )
        renderer.setBounds(0, 0, bounds.width, bounds.height)
        fun layout(component: Component) {
            if (component is Container) {
                component.doLayout()
                component.components.forEach(::layout)
            }
        }
        layout(renderer)
        var component = SwingUtilities.getDeepestComponentAt(renderer, event.x - bounds.x, event.y - bounds.y)
        while (component != null) {
            (component as? JComponent)?.toolTipText?.let { return it }
            if (component === renderer) break
            component = component.parent
        }
        return null
    }
}