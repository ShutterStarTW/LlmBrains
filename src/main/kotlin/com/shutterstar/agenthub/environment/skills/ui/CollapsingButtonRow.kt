package com.shutterstar.agenthub.environment.skills.ui

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A left-aligned button row whose secondary buttons shrink to icon-only when the row is too
 * narrow for every label. Primary buttons (added with [add]) always keep their text; a collapsible
 * one ([addCollapsible]) keeps its text as tooltip/accessible name while collapsed, so the
 * important action (e.g. Promote to Shared) is never pushed out of view by the file buttons.
 */
internal class CollapsingButtonRow(gap: Int) : JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(gap), 0)) {
    private class Collapsible(val button: JButton, val text: String, val icon: Icon, val fullWidth: Int)

    private val collapsibles = mutableListOf<Collapsible>()
    private var laidOutHeight = -1

    init {
        // The wrapped height depends on the width the parent finally gives us; ask the parent to re-measure when it changes.
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                val height = preferredSize.height
                if (height != laidOutHeight) {
                    laidOutHeight = height
                    revalidate()
                }
            }
        })
    }

    /** Whether the collapsible buttons currently show only their icon. Exposed for tests. */
    internal var collapsed = false
        private set

    fun addCollapsible(button: JButton, icon: Icon) {
        val text = button.text
        val tooltip = button.toolTipText
        button.accessibleContext.accessibleName = text
        // Tooltip carries the label once the text is gone; keep the original description too.
        button.toolTipText = if (tooltip.isNullOrBlank()) text else tooltip
        collapsibles += Collapsible(button, text, icon, button.preferredSize.width)
        add(button)
    }

    /** Width every button would need with all labels shown. */
    internal fun fullWidth(): Int {
        val gap = (layout as FlowLayout).hgap
        val widths = components.filter { it.isVisible }.map { component ->
            collapsibles.firstOrNull { it.button === component }?.fullWidth ?: component.preferredSize.width
        }
        val insets = insets
        return widths.sum() + gap * (widths.size + 1) + insets.left + insets.right
    }

    override fun doLayout() {
        val shouldCollapse = collapsibles.isNotEmpty() && width > 0 && fullWidth() > width
        if (shouldCollapse != collapsed) {
            collapsed = shouldCollapse
            collapsibles.forEach { item ->
                item.button.text = if (shouldCollapse) "" else item.text
                item.button.icon = if (shouldCollapse) item.icon else null
            }
        }
        super.doLayout()
    }

    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)

    companion object {
        fun of(gap: Int, build: CollapsingButtonRow.() -> Unit): CollapsingButtonRow =
            CollapsingButtonRow(gap).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                build()
            }
    }
}
