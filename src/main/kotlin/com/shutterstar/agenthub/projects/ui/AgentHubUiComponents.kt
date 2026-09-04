package com.shutterstar.agenthub.projects.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.CodingAgents
import com.shutterstar.agenthub.FaviconLoader
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Insets
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.Border

internal object AgentHubUiComponents {
    const val PANEL_INSET = 8
    const val LIST_LEFT_INSET = 8
    const val TEXT_LEFT_INSET = 12
    const val LIST_ITEM_LEFT_INSET = TEXT_LEFT_INSET - LIST_LEFT_INSET
    const val CONTROL_GAP = 6
    const val SMALL_GAP = 4

    /** Corner radius for the rounded selection/hover pill behind a row, matching a selected tab. */
    val SELECTION_ARC get() = JBUI.scale(8)

    /**
     * Horizontal margin between the rounded pill's outer edge and the row/list bounds. Zero, so
     * the pill spans the full row width — the breathing room around the text comes from
     * [ROW_TEXT_PADDING] instead, which sits *inside* the pill.
     */
    val SELECTION_HORIZONTAL_INSET get() = 0

    /** Vertical margin around the rounded pill — this is what creates the gap between rows. */
    val SELECTION_VERTICAL_INSET get() = JBUI.scale(2)

    /** Padding between the pill's edge and the row's text — same on all four sides. */
    val ROW_TEXT_PADDING get() = JBUI.scale(8)

    fun alignedBorderlessScrollPane(component: Component): JComponent {
        val scrollPane = JBScrollPane(component).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(LIST_LEFT_INSET)
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    /** The standard inset for a two-line "title + gray detail" list row renderer. */
    fun listRowBorder(): Border = JBUI.Borders.empty(ROW_TEXT_PADDING)

    /** Margin for the selection/hover pill wrapping a whole list row (all four sides inset). */
    fun listSelectionInsets(): Insets =
        JBUI.insets(SELECTION_VERTICAL_INSET, SELECTION_HORIZONTAL_INSET, SELECTION_VERTICAL_INSET, SELECTION_HORIZONTAL_INSET)

    data class RowTextColors(
        val foreground: Color,
        val secondaryForeground: Color,
    )

    /** Foreground/secondary-foreground for a row renderer, selected or not. */
    fun rowTextColors(foreground: Color, isSelected: Boolean): RowTextColors = RowTextColors(
        foreground = if (isSelected) selectionTextColor() else foreground,
        secondaryForeground = if (isSelected) selectionTextColor() else JBColor.GRAY,
    )

    /**
     * Fill color for the rounded pill painted behind a row — selected rows get the theme's
     * selection color, hovered-but-unselected rows get the same gray the platform uses for
     * tab/list hover, and idle rows get no fill at all.
     */
    fun rowHighlight(isSelected: Boolean, isHovered: Boolean): Color? = when {
        isSelected -> selectionFillColor()
        isHovered -> JBUI.CurrentTheme.List.Hover.background(true)
        else -> null
    }

    private fun selectionFillColor(): Color = JBUI.CurrentTheme.List.Selection.background(true)

    private fun selectionTextColor(): Color = JBUI.CurrentTheme.List.Selection.foreground(true)

    fun displayName(agentId: String): String =
        CodingAgents.all.firstOrNull { it.id == agentId }?.name ?: agentId

    fun faviconFor(agentId: String): Icon? =
        CodingAgents.all.firstOrNull { it.id == agentId }?.let(FaviconLoader::get)
}
