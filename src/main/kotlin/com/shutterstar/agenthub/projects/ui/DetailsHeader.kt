package com.shutterstar.agenthub.projects.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * The always-visible strip above a details view's tabs: icon + name of the selected entity and
 * up to a few gray context lines (path, Git remote, activity). Shared by the Projects and Agents
 * details so both read the same way — and so the Agents side, which previously had no header at
 * all, names the agent whose details are shown.
 */
internal class DetailsHeader : JPanel() {
    private val titleLabel = AgentHubUiComponents.singleLineText("").apply {
        putClientProperty("html.disable", true)
        font = font.deriveFont(Font.BOLD)
        alignmentX = Component.LEFT_ALIGNMENT
    }
    private val detailLabels = List(MAX_DETAIL_LINES) {
        // Wraps onto more lines (long paths / Git remotes) instead of being cut off with "...".
        AgentHubUiComponents.wrappingStatusText().apply {
            foreground = JBColor.GRAY
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(
            AgentHubUiComponents.PANEL_INSET,
            AgentHubUiComponents.TEXT_LEFT_INSET,
            AgentHubUiComponents.CONTROL_GAP,
            AgentHubUiComponents.PANEL_INSET,
        )
        add(titleLabel)
        detailLabels.forEach(::add)
        set(null, emptyList())
    }

    fun set(
        title: DetailsTitle?,
        details: List<String>,
        placeholder: String = "",
    ) {
        titleLabel.text = title?.text ?: placeholder
        titleLabel.icon = title?.icon
        titleLabel.toolTipText = title?.text
        detailLabels.forEachIndexed { index, label ->
            val text = details.getOrNull(index)
            label.text = text.orEmpty()
            label.toolTipText = text
            label.isVisible = text != null
        }
        revalidate()
        repaint()
    }

    companion object {
        private const val MAX_DETAIL_LINES = 3
    }
}
