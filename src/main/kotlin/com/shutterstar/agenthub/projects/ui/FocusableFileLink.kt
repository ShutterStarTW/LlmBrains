package com.shutterstar.agenthub.projects.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.UIManager

/** A file label with theme-aware hover and a visible keyboard focus outline. */
internal class FocusableFileLink(text: String) : JBLabel(text) {
    private var hovered = false
    init {
        isFocusable = true
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) = repaint()
            override fun focusLost(event: FocusEvent) = repaint()
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(event: MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(event: MouseEvent) { hovered = false; repaint() }
        })
    }

    override fun getPreferredSize(): Dimension = super.getPreferredSize().apply { width = minOf(width, JBUI.scale(520)) }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val paint = graphics.create()
        try {
            paint.color = UIManager.getColor("Component.focusColor") ?: JBUI.CurrentTheme.Link.Foreground.ENABLED
            if (hasFocus()) paint.drawRect(0, 0, maxOf(0, width - 1), maxOf(0, height - insets.bottom - 1))
            if (hovered) {
                val metrics = getFontMetrics(font)
                val baseline = (height - insets.bottom - metrics.height) / 2 + metrics.ascent + 1
                paint.drawLine(insets.left, baseline, minOf(width - insets.right, insets.left + metrics.stringWidth(text)), baseline)
            }
        } finally { paint.dispose() }
    }
}