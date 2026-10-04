package com.shutterstar.agenthub.projects.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants
import javax.swing.text.DefaultCaret

/**
 * The gray info strip under the Environment and Sessions tables: wrapped text that is as tall as its
 * content (up to [MAX_HEIGHT]) and scrolls only beyond that. The height is measured from the
 * PARENT's width, not from this component's own (still unassigned on first show) width, so it is
 * right on the very first layout.
 */
internal class DetailsStrip(accessibleName: String) : JBScrollPane() {
    private val text = WrappedText().apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        font = JBUI.Fonts.label()
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(
            AgentHubUiComponents.CONTROL_GAP,
            AgentHubUiComponents.TEXT_LEFT_INSET,
            AgentHubUiComponents.CONTROL_GAP,
            0,
        )
        (caret as DefaultCaret).updatePolicy = DefaultCaret.NEVER_UPDATE
    }

    val content: String get() = text.text

    init {
        setViewportView(text)
        border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
        viewportBorder = JBUI.Borders.empty()
        viewport.isOpaque = false
        isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        text.accessibleContext.accessibleName = accessibleName
        getAccessibleContext().accessibleName = accessibleName
        isVisible = false
    }

    fun showLines(lines: List<String>) {
        text.text = lines.joinToString("\n")
        text.toolTipText = null
        text.caretPosition = 0
        viewport.viewPosition = java.awt.Point(0, 0)
        isVisible = lines.isNotEmpty()
        (parent as? JComponent)?.let { it.revalidate(); it.repaint() }
        revalidate()
    }

    override fun getPreferredSize(): Dimension {
        val borderInsets = border?.getBorderInsets(this)
        val outer = (parent?.width?.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?: JBUI.scale(DEFAULT_WIDTH))
        val inner = (outer - (borderInsets?.left ?: 0) - (borderInsets?.right ?: 0)).coerceAtLeast(1)
        val wanted = text.heightForWidth(inner) + (borderInsets?.top ?: 0) + (borderInsets?.bottom ?: 0)
        return Dimension(0, minOf(wanted, JBUI.scale(MAX_HEIGHT)))
    }

    private class WrappedText : WidthAwareTextArea() {
        fun heightForWidth(width: Int): Int = preferredSizeForWidth(width).height
    }

    private companion object {
        const val MAX_HEIGHT = 160
        const val DEFAULT_WIDTH = 400
    }
}
