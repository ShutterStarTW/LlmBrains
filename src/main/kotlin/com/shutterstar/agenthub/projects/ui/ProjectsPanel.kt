package com.shutterstar.agenthub.projects.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.environment.discovery.ProjectEnvironmentDiscoveryService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager

class ProjectsPanel(
    currentProject: Project,
    private val agentName: (String) -> String,
    environmentDiscovery: ProjectEnvironmentDiscoveryService = ProjectEnvironmentDiscoveryService(),
) : JPanel(BorderLayout()) {
    private val model = DefaultListModel<DiscoveredProject>()
    private val list = object : JBList<DiscoveredProject>(model) {
        override fun getToolTipText(event: MouseEvent): String? {
            val index = locationToIndex(event.point)
            if (index < 0) return null
            val cellBounds = getCellBounds(index, index) ?: return null
            if (!cellBounds.contains(event.point)) return null
            val renderer = cellRenderer.getListCellRendererComponent(
                this,
                model.getElementAt(index),
                index,
                isSelectedIndex(index),
                false,
            )
            renderer.setBounds(cellBounds)
            renderer.invalidate()
            renderer.validate()
            val relative = Point(event.x - cellBounds.x, event.y - cellBounds.y)
            val deepest = SwingUtilities.getDeepestComponentAt(renderer, relative.x, relative.y)
            return (deepest as? JComponent)?.toolTipText
        }
    }
    private val detailsPanel = ProjectDetailsPanel(currentProject, environmentDiscovery = environmentDiscovery)

    init {
        list.cellRenderer = ProjectRenderer(agentName, ListHoverTracker(list))
        list.emptyText.text = "No projects discovered"
        ToolTipManager.sharedInstance().registerComponent(list)
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) detailsPanel.setProject(list.selectedValue)
        }
        val splitPane = JBSplitter(true, 0.65f)
        splitPane.firstComponent = AgentHubUiComponents.alignedBorderlessScrollPane(list)
        splitPane.secondComponent = detailsPanel
        splitPane.border = null
        add(splitPane, BorderLayout.CENTER)
    }

    fun setProjects(projects: List<DiscoveredProject>, refreshing: Boolean) {
        val selectedId = list.selectedValue?.identity?.id
        model.removeAllElements()
        projects.forEach(model::addElement)
        val selectedIndex = projects.indexOfFirst { it.identity.id == selectedId }
        list.selectedIndex = when {
            selectedIndex >= 0 -> selectedIndex
            projects.isNotEmpty() -> 0
            else -> -1
        }
        if (projects.isEmpty()) detailsPanel.setProject(null)
        list.emptyText.text = if (refreshing) "Discovering projects…" else "No projects discovered"
    }

    private class ProjectRenderer(
        private val agentName: (String) -> String,
        private val hover: ListHoverTracker,
    ) : ListCellRenderer<DiscoveredProject> {
        private val nameLabel = JBLabel()
        private val iconsPanel = JPanel(
            FlowLayout(FlowLayout.LEFT, JBUI.scale(AgentHubUiComponents.SMALL_GAP), 0),
        ).apply { isOpaque = false }
        private val topRow = JPanel(BorderLayout()).apply { isOpaque = false }
        private val pathLabel = JBLabel()
        private val summaryLabel = JBLabel()
        private val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = AgentHubUiComponents.listRowBorder()
            nameLabel.font = nameLabel.font.deriveFont(nameLabel.font.style or java.awt.Font.BOLD)
            pathLabel.foreground = JBColor.GRAY
            summaryLabel.foreground = JBColor.GRAY
            topRow.add(nameLabel, BorderLayout.WEST)
            topRow.add(iconsPanel, BorderLayout.CENTER)
            topRow.alignmentX = Component.LEFT_ALIGNMENT
            pathLabel.alignmentX = Component.LEFT_ALIGNMENT
            summaryLabel.alignmentX = Component.LEFT_ALIGNMENT
            add(topRow)
            add(pathLabel)
            add(summaryLabel)
        }
        private val wrapper = RoundedSelectionPanel.wrap(content).apply {
            selectionArc = AgentHubUiComponents.SELECTION_ARC
            selectionInsets = AgentHubUiComponents.listSelectionInsets()
        }

        override fun getListCellRendererComponent(
            list: JList<out DiscoveredProject>,
            value: DiscoveredProject,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            nameLabel.text = value.name
            pathLabel.text = value.path ?: value.gitRemote ?: "Unknown location"
            iconsPanel.removeAll()
            value.agents.forEach { relation ->
                val icon = AgentHubUiComponents.faviconFor(relation.agentId) ?: return@forEach
                iconsPanel.add(JLabel(icon).apply { toolTipText = agentName(relation.agentId) })
            }
            val totalSessions = value.agents.sumOf { it.sessionCount }
            val activity = value.lastActivity?.let(AgentHubUiFormat.dateTime::format) ?: "Unknown"
            summaryLabel.text = "$totalSessions sessions  |  Last activity: $activity"
            wrapper.background = list.background
            val colors = AgentHubUiComponents.rowTextColors(list.foreground, isSelected)
            wrapper.selectionColor = AgentHubUiComponents.rowHighlight(isSelected, hover.isHovered(index))
            nameLabel.foreground = colors.foreground
            pathLabel.foreground = colors.secondaryForeground
            summaryLabel.foreground = colors.secondaryForeground
            return wrapper
        }

    }
}
