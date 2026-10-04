package com.shutterstar.agenthub.projects.ui

import java.awt.Dimension
import javax.swing.JTextArea

/** Measures wrapping without resizing a mounted child during its parent's layout calculation. */
internal open class WidthAwareTextArea : JTextArea() {
    private val measuringArea by lazy { JTextArea() }

    override fun getPreferredSize(): Dimension {
        val owner = parent
        val availableWidth = owner?.width?.takeIf { it > 0 }
            ?.minus(owner.insets.left + owner.insets.right) ?: width
        return if (availableWidth > 0) preferredSizeForWidth(availableWidth) else super.getPreferredSize()
    }

    protected fun preferredSizeForWidth(availableWidth: Int): Dimension {
        // Only the detached measurer may invalidate itself. Resizing this component here would
        // clear BoxLayout's request arrays while BoxLayout is still filling them.
        val measure = measuringArea
        if (measure.document !== document) measure.document = document
        measure.font = font
        measure.border = border
        measure.margin = margin
        measure.lineWrap = lineWrap
        measure.wrapStyleWord = wrapStyleWord
        measure.tabSize = tabSize
        measure.rows = rows
        measure.columns = columns
        measure.componentOrientation = componentOrientation
        measure.setSize(availableWidth, Short.MAX_VALUE.toInt())
        return measure.preferredSize.apply { width = availableWidth }
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}