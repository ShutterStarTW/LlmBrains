package com.shutterstar.agenthub.projects.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.AgentSettingsState
import com.shutterstar.agenthub.environment.discovery.AgentEnvironmentDiscoveryService
import com.shutterstar.agenthub.environment.discovery.ProjectEnvironmentDiscoveryService
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.ui.EnvironmentPanel
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager

internal class AgentsPanel(
    private val currentProject: Project,
    environmentDiscovery: ProjectEnvironmentDiscoveryService,
    agentEnvironmentDiscovery: AgentEnvironmentDiscoveryService,
    openSkill: (SkillScope, String, String?, DiscoveredProject?) -> Unit = { _, _, _, _ -> },
    /** Double-click / Enter on a project row: jump to that project on the Projects tab. */
    openProject: (projectId: String) -> Unit = {},
    sessionActions: SessionActions = SessionActions(),
    splitProportion: SharedSplitProportion = SharedSplitProportion(),
    private val retryEnvironment: (String) -> Unit = {},
    private val hasEnvironmentFailure: (String) -> Boolean = { false },
    private val installationStatus: (String) -> String = { agentId ->
        val settings = AgentSettingsState.getInstance()
        agentInstallationStatus(agentId, settings.getDetectionResults(), settings.getDetectionTimestamp())
    },
) : JPanel(BorderLayout()) {
    private val model = DefaultListModel<DiscoveredAgentSummary>()
    private val list = RendererToolTipList(model)
    private val detailsPanel = AgentDetailsPanel(
        currentProject,
        environmentDiscovery,
        agentEnvironmentDiscovery,
        openSkill,
        openProject,
        sessionActions,
        installationStatus,
    )
    private var projects: List<DiscoveredProject> = emptyList()
    private var environmentLabels: Map<String, String> = emptyMap()
    private val retryLink = com.intellij.ui.components.ActionLink("Retry environment discovery") { list.selectedValue?.agentId?.let(retryEnvironment) }.apply { isVisible = false }
    private var updatingModel = false
    private val responsive = ResponsiveMasterDetail(
        list = AgentHubUiComponents.alignedBorderlessScrollPane(list),
        details = detailsPanel,
        backButtonText = "Agents",
        hasSelection = { list.selectedValue != null },
        onBack = { list.requestFocusInWindow() },
        selectionTitle = { list.selectedValue?.let { DetailsTitle(it.name, AgentHubUiComponents.faviconFor(it.agentId)) } },
        splitProportion = splitProportion,
    )

    /** Invoked by the empty-list "Clear search" link; the tool window clears its shared search field. */
    var onClearSearch: () -> Unit = {}

    /** Fires while a narrow layout shows an agent's details alone — the search box has nothing to narrow then. */
    var onDetailsOnlyChanged: (Boolean) -> Unit = {}

    init {
        responsive.onDetailsOnlyChanged = { onDetailsOnlyChanged(it) }
        list.cellRenderer = AgentRenderer(ListHoverTracker(list)) { environmentLabels[it] }
        list.emptyText.text = "No agents discovered"
        ToolTipManager.sharedInstance().registerComponent(list)
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting && !updatingModel) {
                detailsPanel.setAgent(list.selectedValue, projects, list.selectedValue?.agentId?.let(environmentLabels::get))
                responsive.selectionChanged()
                retryLink.isVisible = list.selectedValue?.agentId?.let(hasEnvironmentFailure) == true
            }
        }
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "show-agent-details")
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "show-agent-details")
        list.actionMap.put("show-agent-details", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) = responsive.showDetailsNow()
        })
        RowContextMenus.install(list, ::contextMenu)
        list.addMouseListener(object : MouseAdapter() {


            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                val index = list.locationToIndex(event.point)
                if (index < 0 || list.getCellBounds(index, index)?.contains(event.point) != true) return
                responsive.showDetailsNow()
            }

        })
        add(retryLink, BorderLayout.NORTH)
        add(responsive.component, BorderLayout.CENTER)
    }

    private fun contextMenu(agent: DiscoveredAgentSummary): JPopupMenu = JPopupMenu().apply {
        add(JMenuItem("Show details").apply { addActionListener { responsive.showDetailsNow() } })
        val url = com.shutterstar.agenthub.CodingAgents.byId(agent.agentId)?.url
        add(JMenuItem("Agent documentation").apply {
            isEnabled = !url.isNullOrBlank()
            addActionListener { url?.let(com.intellij.ide.BrowserUtil::browse) }
        })
        if (hasEnvironmentFailure(agent.agentId)) {
            addSeparator()
            add(JMenuItem("Retry environment discovery").apply { addActionListener { retryEnvironment(agent.agentId) } })
        }
    }

    /** A fresh tab visit starts at the list and its first agent. */
    fun showDefaultContent() {
        responsive.showList()
        if (model.size > 0) {
            list.selectedIndex = 0
            list.ensureIndexIsVisible(0)
        }
        detailsPanel.showDefaultTab()
    }
    fun setAgents(
        agents: List<DiscoveredAgentSummary>,
        projects: List<DiscoveredProject>,
        refreshing: Boolean,
        filtered: Boolean = false,
    ) {
        this.projects = projects
        val selectedAgentId = list.selectedValue?.agentId
        updatingModel = true
        try {
            model.removeAllElements()
            agents.forEach(model::addElement)
            val selectedIndex = agents.indexOfFirst { it.agentId == selectedAgentId }
            list.selectedIndex = when {
                selectedIndex >= 0 -> selectedIndex
                agents.isNotEmpty() -> 0
                else -> -1
            }
        } finally {
            updatingModel = false
        }
        detailsPanel.setAgent(list.selectedValue, projects, list.selectedValue?.agentId?.let(environmentLabels::get))
        responsive.selectionChanged()
        retryLink.isVisible = list.selectedValue?.agentId?.let(hasEnvironmentFailure) == true
        list.emptyText.text = ProjectIndexUiModel.emptyText("agents", refreshing, filtered)
        if (filtered && !refreshing) {
            list.emptyText.appendLine("Clear search", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { onClearSearch() }
        }
    }

    fun setEnvironmentLabels(labels: Map<String, String>) {
        environmentLabels = labels
        retryLink.isVisible = list.selectedValue?.agentId?.let(hasEnvironmentFailure) == true
        // The optional third line changes cell height after background discovery completes.
        if (model.size > 0) model.setElementAt(model.getElementAt(0), 0)
        list.revalidate()
        list.repaint()
        detailsPanel.setEnvironmentLabel(list.selectedValue?.agentId?.let(labels::get))
    }

    fun dispose() = detailsPanel.dispose()

    fun refreshDetails() = detailsPanel.setAgent(list.selectedValue, projects, list.selectedValue?.agentId?.let(environmentLabels::get))

    private class AgentRenderer(
        private val hover: ListHoverTracker,
        private val environmentLabel: (String) -> String?,
    ) : ListCellRenderer<DiscoveredAgentSummary> {
        private val nameLabel = JBLabel()
        private val totalsLabel = WrappedRowText()
        private val environmentInfoLabel = WrappedRowText()
        private val content = AgentHubUiComponents.verticalBox(opaque = false, border = AgentHubUiComponents.listRowBorder()).apply {
            nameLabel.font = nameLabel.font.deriveFont(nameLabel.font.style or Font.BOLD)
            nameLabel.alignmentX = Component.LEFT_ALIGNMENT
            totalsLabel.foreground = JBColor.GRAY
            environmentInfoLabel.foreground = JBColor.GRAY
            add(nameLabel)
            add(totalsLabel)
            add(environmentInfoLabel)
        }
        private val wrapper = RoundedSelectionPanel.wrap(content).apply {
            selectionArc = JBUI.scale(AgentHubUiComponents.SELECTION_ARC)
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
            nameLabel.accessibleContext.accessibleName = value.name
            nameLabel.icon = AgentHubUiComponents.faviconFor(value.agentId)
            val activity = value.lastActivity?.let(AgentHubUiFormat.dateTime::format) ?: "Unknown"
            totalsLabel.text =
                "${value.projectCount} projects · ${value.sessionCount} sessions · Last activity: $activity"
            environmentInfoLabel.text = environmentLabel(value.agentId).orEmpty()
            environmentInfoLabel.isVisible = environmentInfoLabel.text.isNotEmpty()
            wrapper.background = list.background
            WrappedRowText.prepareRow(list, wrapper, content, listOf(totalsLabel, environmentInfoLabel))
            val colors = AgentHubUiComponents.rowTextColors(list.foreground, isSelected)
            wrapper.selectionColor = AgentHubUiComponents.rowHighlight(isSelected, hover.isHovered(index))
            nameLabel.foreground = colors.foreground
            totalsLabel.foreground = colors.secondaryForeground
            environmentInfoLabel.foreground = colors.secondaryForeground
            return wrapper
        }
    }

    /**
     * Same shape as [ProjectDetailsPanel]: a header naming the entity, then Projects / Sessions /
     * Environment — the project side's Overview is replaced by the agent's project list, and the
     * other two tabs are the very same components, just filtered by agent instead of by project.
     * Nothing in here names the agent again below the header.
     */
    private class AgentDetailsPanel(
        currentProject: Project,
        environmentDiscovery: ProjectEnvironmentDiscoveryService,
        agentEnvironmentDiscovery: AgentEnvironmentDiscoveryService,
        openSkill: (SkillScope, String, String?, DiscoveredProject?) -> Unit,
        private val openProject: (String) -> Unit,
        sessionActions: SessionActions,
        private val installationStatus: (String) -> String,
    ) : JPanel(BorderLayout()) {
        private val header = DetailsHeader()
        private val projectModel = DefaultListModel<AgentProjectUsage>()
        private val projectList = RendererToolTipList(projectModel)
        private val sessionsPanel = SessionsPanel(actions = sessionActions)
        private val environmentPanel = EnvironmentPanel(currentProject, environmentDiscovery, agentEnvironmentDiscovery, openSkill)
        private val tabs = LeftAlignedTabbedPane()
        private var shownAgentId: String? = null
        private var shownAgent: DiscoveredAgentSummary? = null
        private var environmentLabel: String? = null

        init {
            border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
            projectList.cellRenderer = ProjectUsageRenderer(ListHoverTracker(projectList))
            projectList.emptyText.text = "Select an agent"
            ToolTipManager.sharedInstance().registerComponent(projectList)
            projectList.accessibleContext.accessibleName = "Projects this agent worked on"
            projectList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-project")
            projectList.actionMap.put("open-project", object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) {
                    projectList.selectedValue?.let { openProject(it.projectId) }
                }
            })
            RowContextMenus.install(projectList) { usage ->
                JPopupMenu().apply {
                    add(JMenuItem("Show project details").apply { addActionListener { openProject(usage.projectId) } })
                    add(JMenuItem("Copy Path").apply {
                        isEnabled = usage.projectPath != null
                        addActionListener { AgentHubFileActions.copyPath(usage.projectPath) }
                    })
                }
            }
            projectList.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(event) || event.clickCount != 2) return
                    val index = projectList.locationToIndex(event.point)
                    if (index < 0 || projectList.getCellBounds(index, index)?.contains(event.point) != true) return
                    openProject(projectModel.getElementAt(index).projectId)
                }
            })
            tabs.addTab("Projects", AgentHubUiComponents.alignedBorderlessScrollPane(projectList))
            tabs.addTab("Sessions", sessionsPanel)
            tabs.addTab("Environment", environmentPanel)
            add(header, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }

        fun showDefaultTab() = tabs.selectFirst()

        fun dispose() = environmentPanel.dispose()

        fun setAgent(
            agent: DiscoveredAgentSummary?,
            projects: List<DiscoveredProject>,
            environmentInfo: String?,
        ) {
            // A *different* agent opens on the default (first) tab; a refresh of the same agent
            // keeps whatever tab the user was reading.
            val agentChanged = agent?.agentId != shownAgentId
            if (agentChanged) tabs.selectFirst()
            shownAgentId = agent?.agentId
            shownAgent = agent
            environmentLabel = environmentInfo
            updateHeader()
            projectModel.removeAllElements()
            agent?.projects.orEmpty().forEach(projectModel::addElement)
            if (agentChanged) AgentHubUiComponents.scrollToTop(projectList)
            projectList.emptyText.text = if (agent == null) "Select an agent" else "No projects discovered"
            val scope = agent?.let { DetailsScope.ForAgent(it.agentId, projects) }
            sessionsPanel.setScope(scope)
            environmentPanel.setScope(scope)
        }

        fun setEnvironmentLabel(label: String?) {
            environmentLabel = label
            updateHeader()
        }

        private fun updateHeader() {
            val agent = shownAgent
            header.set(
                agent?.let { DetailsTitle(it.name, AgentHubUiComponents.faviconFor(it.agentId)) },
                agent?.let {
                    val activity = it.lastActivity?.let(AgentHubUiFormat.dateTime::format) ?: "Unknown"
                    listOfNotNull(
                        "${it.projectCount} projects · ${it.sessionCount} sessions · Last activity: $activity",
                        installationStatus(it.agentId),
                        environmentLabel,
                    )
                }.orEmpty(),
                placeholder = "Select an agent",
            )
        }
    }

    private class ProjectUsageRenderer(
        private val hover: ListHoverTracker,
    ) : ListCellRenderer<AgentProjectUsage> {
        private val nameLabel = JBLabel()
        private val openHint = JBLabel("↗").apply {
            toolTipText = "Double-click to open project details"
            accessibleContext.accessibleName = "Open project details"
        }
        private val topRow = JPanel(BorderLayout()).apply { isOpaque = false }
        private val detailLabels = List(MAX_DETAIL_LINES) { WrappedRowText() }
        private val content = AgentHubUiComponents.verticalBox(opaque = false, border = AgentHubUiComponents.listRowBorder()).apply {
            nameLabel.font = nameLabel.font.deriveFont(nameLabel.font.style or Font.BOLD)
            topRow.add(nameLabel, BorderLayout.CENTER)
            topRow.add(openHint, BorderLayout.EAST)
            topRow.alignmentX = Component.LEFT_ALIGNMENT
            detailLabels.forEach { it.foreground = JBColor.GRAY }
        }
        private val wrapper = RoundedSelectionPanel.wrap(content).apply {
            selectionArc = JBUI.scale(AgentHubUiComponents.SELECTION_ARC)
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
            nameLabel.toolTipText = "Double-click to open project details"
            val activity = value.lastActivity?.let(AgentHubUiFormat.dateTime::format) ?: "Unknown"

            content.removeAll()
            content.add(topRow)
            agentProjectUsageDetailLines(value, activity).forEachIndexed { detailIndex, text ->
                detailLabels[detailIndex].text = text
                content.add(detailLabels[detailIndex])
            }

            wrapper.background = list.background
            WrappedRowText.prepareRow(list, wrapper, content, detailLabels)
            val colors = AgentHubUiComponents.rowTextColors(list.foreground, isSelected)
            wrapper.selectionColor = AgentHubUiComponents.rowHighlight(isSelected, hover.isHovered(index))
            nameLabel.foreground = colors.foreground
            openHint.foreground = colors.secondaryForeground
            detailLabels.forEach { it.foreground = colors.secondaryForeground }
            wrapper.accessibleContext.accessibleName =
                "${value.projectName}, ${value.sessionCount} sessions. Open project details with Enter or double-click."
            return wrapper
        }

        companion object {
            private const val MAX_DETAIL_LINES = 4
        }
    }
}

internal fun agentInstallationStatus(agentId: String, results: Map<String, Boolean>?, checkedAtMillis: Long): String {
    val installed = results?.get(agentId) ?: return "Installation not checked"
    if (checkedAtMillis <= 0) return "Installation not checked"
    val checkedAt = AgentHubUiFormat.dateTime.format(Instant.ofEpochMilli(checkedAtMillis))
    return if (installed) "Installed at last check · $checkedAt" else "Not detected at last check · $checkedAt"
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
