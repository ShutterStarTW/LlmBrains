package com.shutterstar.agenthub.projects.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/** What a [DetailRowRenderer] shows for one list value. */
internal class DetailRow(
    val title: String,
    /** Grey lines under the title; blank lines are skipped. */
    val details: List<String>,
    val titleIcon: Icon? = null,
    val titleTooltip: String? = null,
    val titleAccessibleName: String? = null,
    val rowAccessibleName: String? = null,
)

/**
 * The shared shape of the Projects, Agents and agent→project list rows: a bold title (with an optional
 * trailing component on the right), then wrapped grey detail lines, all inside the rounded
 * selection/hover pill. Subclasses only turn a value into a [DetailRow] and, when they have one,
 * fill and colour their trailing component.
 */
internal abstract class DetailRowRenderer<T>(
    private val hover: ListHoverTracker,
    maxDetailLines: Int,
) : ListCellRenderer<T> {
    private val nameLabel = JBLabel()
    private val detailLabels = List(maxDetailLines) { WrappedRowText() }

    /** Title row; subclasses add their trailing component at `BorderLayout.EAST` in `init`. */
    protected val topRow = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private val content = AgentHubUiComponents.verticalBox(opaque = false, border = AgentHubUiComponents.listRowBorder()).apply {
        nameLabel.font = nameLabel.font.deriveFont(nameLabel.font.style or Font.BOLD)
        topRow.add(nameLabel, BorderLayout.CENTER)
        detailLabels.forEach {
            it.foreground = JBColor.GRAY
            it.alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    private val wrapper = RoundedSelectionPanel.wrap(content).apply {
        selectionArc = JBUI.scale(AgentHubUiComponents.SELECTION_ARC)
        selectionInsets = AgentHubUiComponents.listSelectionInsets()
    }

    protected abstract fun bind(value: T): DetailRow

    /** Called on every render, after [bind]: rebuild the trailing component for [value]. */
    protected open fun bindTrailing(value: T) {}

    /** Called on every render with the colour the grey lines use (it follows the selection state). */
    protected open fun styleTrailing(secondaryForeground: Color) {}

    override fun getListCellRendererComponent(
        list: JList<out T>,
        value: T,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val row = bind(value)
        nameLabel.text = row.title
        nameLabel.icon = row.titleIcon
        nameLabel.toolTipText = row.titleTooltip
        row.titleAccessibleName?.let { nameLabel.accessibleContext.accessibleName = it }
        row.rowAccessibleName?.let { wrapper.accessibleContext.accessibleName = it }
        bindTrailing(value)

        content.removeAll()
        content.add(topRow)
        row.details.filter { it.isNotBlank() }.take(detailLabels.size).forEachIndexed { detailIndex, text ->
            detailLabels[detailIndex].text = text
            content.add(detailLabels[detailIndex])
        }

        wrapper.background = list.background
        WrappedRowText.prepareRow(list, wrapper, content, detailLabels)
        val colors = AgentHubUiComponents.rowTextColors(list.foreground, isSelected)
        wrapper.selectionColor = AgentHubUiComponents.rowHighlight(isSelected, hover.isHovered(index))
        nameLabel.foreground = colors.foreground
        detailLabels.forEach { it.foreground = colors.secondaryForeground }
        styleTrailing(colors.secondaryForeground)
        return wrapper
    }
}
