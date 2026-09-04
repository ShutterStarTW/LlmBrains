package com.shutterstar.agenthub.projects.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.JPanel

/**
 * Paints a rounded-rectangle fill behind its wrapped content, matching the platform's
 * "selected tab" look for list/table rows.
 *
 * IntelliJ Platform ships an equivalent (`com.intellij.ui.popup.list.SelectablePanel`), but it's
 * marked `@ApiStatus.Experimental` — the Plugin Verifier flags every call into it as a
 * forward-compatibility risk. This is a small, self-contained replacement built only on stable
 * `java.awt`/`javax.swing` APIs.
 */
internal class RoundedSelectionPanel private constructor(content: Component) : JPanel(BorderLayout()) {
    enum class Corners {
        ALL, LEFT, RIGHT, NONE
    }

    var selectionColor: Color? = null
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }
    var selectionArc: Int = 0
    var selectionInsets: Insets = Insets(0, 0, 0, 0)
    var selectionArcCorners: Corners = Corners.ALL

    init {
        // Opaque and self-filling: the enclosing JList/JTable UI delegate paints its own
        // (square, non-overridable via the plain `selectionBackground` property) selection
        // background into these same cell bounds *before* handing off to this renderer. Filling
        // the whole bounds ourselves first guarantees that square paint is fully covered, no
        // matter which internal color it used.
        isOpaque = true
        add(content, BorderLayout.CENTER)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = background
            g2.fillRect(0, 0, width, height)
            val color = selectionColor
            if (color != null) {
                g2.color = color
                g2.fill(roundedShape())
            }
        } finally {
            g2.dispose()
        }
    }

    private fun roundedShape(): Path2D {
        val x = selectionInsets.left.toDouble()
        val y = selectionInsets.top.toDouble()
        val w = (width - selectionInsets.left - selectionInsets.right).toDouble().coerceAtLeast(0.0)
        val h = (height - selectionInsets.top - selectionInsets.bottom).toDouble().coerceAtLeast(0.0)
        val arc = selectionArc.toDouble().coerceAtMost(minOf(w, h) / 2).coerceAtLeast(0.0)
        val roundLeft = selectionArcCorners == Corners.ALL || selectionArcCorners == Corners.LEFT
        val roundRight = selectionArcCorners == Corners.ALL || selectionArcCorners == Corners.RIGHT

        val path = Path2D.Double()
        if (roundLeft) {
            path.moveTo(x, y + arc)
            path.quadTo(x, y, x + arc, y)
        } else {
            path.moveTo(x, y)
        }
        if (roundRight) {
            path.lineTo(x + w - arc, y)
            path.quadTo(x + w, y, x + w, y + arc)
        } else {
            path.lineTo(x + w, y)
        }
        if (roundRight) {
            path.lineTo(x + w, y + h - arc)
            path.quadTo(x + w, y + h, x + w - arc, y + h)
        } else {
            path.lineTo(x + w, y + h)
        }
        if (roundLeft) {
            path.lineTo(x + arc, y + h)
            path.quadTo(x, y + h, x, y + h - arc)
        } else {
            path.lineTo(x, y + h)
        }
        path.closePath()
        return path
    }

    companion object {
        fun wrap(content: Component): RoundedSelectionPanel = RoundedSelectionPanel(content)
    }
}
