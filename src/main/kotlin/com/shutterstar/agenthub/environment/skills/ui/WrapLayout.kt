package com.shutterstar.agenthub.environment.skills.ui

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

/**
 * A [FlowLayout] whose preferred height accounts for wrapping, so a row that is too narrow for
 * all its components grows taller instead of clipping the ones that no longer fit (a plain
 * `FlowLayout` always reports a single row, which a `BoxLayout` column then cuts off).
 *
 * The width used for the estimate is the container's own; before it has one, the nearest
 * ancestor's, and finally [maxPreferredWidth] (so a row inside a dialog stays bounded instead of
 * asking for one very long line).
 */
internal class WrapLayout(
    align: Int = LEFT,
    hgap: Int = 5,
    vgap: Int = 5,
    private val maxPreferredWidth: Int = Int.MAX_VALUE,
) : FlowLayout(align, hgap, vgap) {
    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, preferred = true)

    fun preferredLayoutSize(target: Container, availableWidth: Int): Dimension =
        layoutSize(target, preferred = true, availableWidth = availableWidth)

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, preferred = false).apply { width -= hgap + 1 }

    private fun layoutSize(target: Container, preferred: Boolean, availableWidth: Int? = null): Dimension = synchronized(target.treeLock) {
        val available = availableWidth ?: generateSequence(target as Component?) { it.parent }
            .map { it.width }
            .firstOrNull { it > 0 } ?: maxPreferredWidth
        val insets = target.insets
        val horizontal = hgap * 2 + insets.left + insets.right
        val maxRowWidth = minOf(available, maxPreferredWidth) - horizontal
        val size = Dimension(0, 0)
        var rowWidth = 0
        var rowHeight = 0
        fun endRow() {
            size.width = maxOf(size.width, rowWidth)
            if (size.height > 0) size.height += vgap
            size.height += rowHeight
        }
        target.components.filter { it.isVisible }.forEach { component ->
            val each = if (preferred) component.preferredSize else component.minimumSize
            if (rowWidth > 0 && rowWidth + hgap + each.width > maxRowWidth) {
                endRow()
                rowWidth = 0
                rowHeight = 0
            }
            if (rowWidth > 0) rowWidth += hgap
            rowWidth += each.width
            rowHeight = maxOf(rowHeight, each.height)
        }
        endRow()
        size.width += horizontal
        size.height += insets.top + insets.bottom + vgap * 2
        size
    }
}
