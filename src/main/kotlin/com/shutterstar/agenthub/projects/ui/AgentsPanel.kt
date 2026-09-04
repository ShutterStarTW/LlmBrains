package com.shutterstar.agenthub.projects.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.environment.ui.AgentEnvironmentPanel
import com.shutterstar.agenthub.environment.discovery.AgentEnvironmentDiscoveryService
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

class AgentsPanel(
    private val currentProject: Project,
    environmentDiscovery: AgentEnvironmentDiscoveryService,
) : JPanel(BorderLayout()) {
    private val model = DefaultListModel<DiscoveredAgentSummary>()
    private val list = JBList(model)
    private val detailsPanel = AgentDetailsPanel(currentProject, environmentDiscovery)
    private var projects: List<DiscoveredProject> = emptyList()

    init {
        list.cellRenderer = AgentRenderer(ListHoverTracker(list))
        list.emptyText.text = "No agents discovered"
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) detailsPanel.setAgent(list.selectedValue, projects)
        }
        val splitPane = JBSplitter(true, 0.4f)
        splitPane.firstComponent = AgentHubUiComponents.alignedBorderlessScrollPane(list)
        splitPane.secondComponent = detailsPanel
        splitPane.border = null
        add(splitPane, BorderLayout.CENTER)
    }

    fun setAgents(
        agents: List<DiscoveredAgentSummary>,
        projects: List<DiscoveredProject>,
        refreshing: Boolean,
    ) {
        this.projects = projects
        val selectedAgentId = list.selectedValue?.agentId
        model.removeAllElements()
        agents.forEach(model::addElement)
        val selectedIndex = agents.indexOfFirst { it.agentId == selectedAgentId }
        list.selectedIndex = when {
            selectedIndex >= 0 -> selectedIndex
            agents.isNotEmpty() -> 0
            else -> -1
        }
        if (agents.isEmpty()) detailsPanel.setAgent(null, projects)
        list.emptyText.text = if (refreshing) "Discovering agents…" else "No agents discovered"
    }

    private class AgentRenderer(
        private val hover: ListHoverTracker,
    ) : ListCellRenderer<DiscoveredAgentSummary> {
        private val nameLabel = JBLabel()
        private val totalsLabel = JBLabel()
        private val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = AgentHubUiComponents.listRowBorder()
            nameLabel.font = nameLabel.font.deriveFont(nameLabel.font.style or java.awt.Font.BOLD)
            totalsLabel.foreground = JBColor.GRAY
            add(nameLabel)
            add(totalsLabel)
        }
        private val wrapper = RoundedSelectionPanel.wrap(content).apply {
            selectionArc = AgentHubUiComponents.SELECTION_ARC
            selectionInsets = AgentHubUiComponents.listSelectionInsets()
        }

        override fun getListCellRendererComponent(
            list: JList<out DiscoveredAgentSummary>,
            value: DiscoveredAgentSummary,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            nameLabel.text = value.name
            nameLabel.icon = AgentHubUiComponents.faviconFor(value.agentId)
            val activity = value.lastActivity?.let(AgentHubUiFormat.dateTime::format) ?: "Unknown"
            totalsLabel.text =
                "${value.projectCount} projects · ${value.sessionCount} sessions · Last activity: $activity"
            wrapper.background = list.background
            val colors = AgentHubUiComponents.rowTextColors(list.foreground, isSelected)
            wrapper.selectionColor = AgentHubUiComponents.rowHighlight(isSelected, hover.isHovered(index))
            nameLabel.foreground = colors.foreground
            totalsLabel.foreground = colors.secondaryForeground
            return wrapper
        }

    }

    private class AgentDetailsPanel(
        currentProject: Project,
        environmentDiscovery: AgentEnvironmentDiscoveryService,
    ) : JPanel(BorderLayout()) {
        private val projectModel = DefaultListModel<AgentProjectUsage>()
        private val projectList = JBList(projectModel)
        private val environmentPanel = AgentEnvironmentPanel(currentProject, environmentDiscovery)

        init {
            border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
            projectList.cellRenderer = ProjectUsageRenderer(ListHoverTracker(projectList))
            projectList.emptyText.text = "Select an agent"
            val tabs = LeftAlignedTabbedPane()
            tabs.addTab("Projects", AgentHubUiComponents.alignedBorderlessScrollPane(projectList))
            tabs.addTab("Environment", environmentPanel)
            add(tabs, BorderLayout.CENTER)
        }

        fun setAgent(
            agent: DiscoveredAgentSummary?,
            projects: List<DiscoveredProject>,
        ) {
            projectModel.removeAllElements()
            agent?.projects.orEmpty().forEach(projectModel::addElement)
            projectList.emptyText.text = if (agent == null) "Select an agent" else "No projects discovered"
            environmentPanel.setAgent(agent?.agentId, projects)
        }
    }

    private class ProjectUsageRenderer(
        private val hover: ListHoverTracker,
    ) : ListCellRenderer<AgentProjectUsage> {
        private val nameLabel = JBLabel()
        private val detailLabels = List(MAX_DETAIL_LINES) { JBLabel() }
        private val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = AgentHubUiComponents.listRowBorder()
            nameLabel.font = nameLabel.font.deriveFont(nameLabel.font.style or java.awt.Font.BOLD)
            detailLabels.forEach { it.foreground = JBColor.GRAY }
        }
        private val wrapper = RoundedSelectionPanel.wrap(content).apply {
            selectionArc = AgentHubUiComponents.SELECTION_ARC
            selectionInsets = AgentHubUiComponents.listSelectionInsets()
        }

        override fun getListCellRendererComponent(
            list: JList<out AgentProjectUsage>,
            value: AgentProjectUsage,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            nameLabel.text = "${value.projectName} · ${value.sessionCount} sessions"
            val activity = value.lastActivity?.let(AgentHubUiFormat.dateTime::format) ?: "Unknown"

            content.removeAll()
            content.add(nameLabel)
            agentProjectUsageDetailLines(value, activity).forEachIndexed { detailIndex, text ->
                detailLabels[detailIndex].text = text
                content.add(detailLabels[detailIndex])
            }

            wrapper.background = list.background
            val colors = AgentHubUiComponents.rowTextColors(list.foreground, isSelected)
            wrapper.selectionColor = AgentHubUiComponents.rowHighlight(isSelected, hover.isHovered(index))
            nameLabel.foreground = colors.foreground
            detailLabels.forEach { it.foreground = colors.secondaryForeground }
            return wrapper
        }

        companion object {
            private const val MAX_DETAIL_LINES = 4
        }
    }

}

internal fun agentProjectUsageDetailLines(
    usage: AgentProjectUsage,
    formattedActivity: String,
): List<String> {
    val projectPath = usage.projectPath?.trim()?.takeIf { it.isNotEmpty() }
    val gitRemote = usage.gitRemote?.trim()?.takeIf { it.isNotEmpty() }
    return buildList {
        if (projectPath == null && gitRemote == null) {
            add("Unknown location")
        } else {
            projectPath?.let { add("Path: $it") }
            gitRemote?.let { add("Git: $it") }
        }
        usage.currentBranch?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Branch: $it") }
        add("Last activity: $formattedActivity")
    }
}
