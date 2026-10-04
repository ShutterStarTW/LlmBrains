package com.shutterstar.agenthub.projects.ui

import java.awt.Component
import java.awt.Dimension
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JTextArea
import javax.swing.UIManager

/**
 * A gray info line of a list-row renderer that wraps onto more lines instead of being cut with "...".
 *
 * A renderer is only a rubber stamp, so it cannot follow a parent's width the way a live component
 * does: the renderer hands the width it may use in [wrapWidth] on every call. Long unbroken values
 * (paths, Git remotes) are broken at any character when no space is available.
 */
internal class WrappedRowText : WidthAwareTextArea() {
    var wrapWidth: Int = 0

    init {
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = null
        margin = Insets(0, 0, 0, 0)
        font = UIManager.getFont("Label.font")
        alignmentX = Component.LEFT_ALIGNMENT
    }

    override fun getPreferredSize(): Dimension {
        // Before the list has a width there is nothing to wrap against: report one line.
        if (wrapWidth <= 0) return Dimension(0, getFontMetrics(font).height)
        return preferredSizeForWidth(wrapWidth)
    }

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    companion object {
        /**
         * Call from every `getListCellRendererComponent`: gives [lines] the width they may wrap at
         * and drops [content]'s cached BoxLayout sizes. A renderer is never validated, so a changed
         * text or width does not invalidate its parents by itself and the BoxLayout would keep
         * reporting the first row's height for every row.
         */
        fun prepareRow(
            list: JList<*>,
            wrapper: RoundedSelectionPanel,
            content: JComponent,
            lines: List<WrappedRowText>,
        ) {
            val width = availableWidth(list, wrapper, content)
            lines.forEach { it.wrapWidth = width }
            content.invalidate()
        }

        /** Width available to text inside a row: the list width minus the pill and content insets. */
        private fun availableWidth(list: JList<*>, wrapper: RoundedSelectionPanel, content: JComponent): Int {
            if (list.width <= 0) return 0
            val border = content.border?.getBorderInsets(content)
            val inset = wrapper.selectionInsets.left + wrapper.selectionInsets.right +
                (border?.left ?: 0) + (border?.right ?: 0)
            return (list.width - inset).coerceAtLeast(MIN_WIDTH)
        }

        private const val MIN_WIDTH = 40
    }
}
