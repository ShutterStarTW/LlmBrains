package com.shutterstar.agenthub.projects.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.environment.discovery.ProjectEnvironmentDiscoveryService
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager

internal class ProjectsPanel(
    private val currentProject: Project,
    private val agentName: (String) -> String,
    environmentDiscovery: ProjectEnvironmentDiscoveryService = ProjectEnvironmentDiscoveryService(),
    openSkill: (SkillScope, String, String?, DiscoveredProject?) -> Unit = { _, _, _, _ -> },
    sessionActions: SessionActions = SessionActions(),
    splitProportion: SharedSplitProportion = SharedSplitProportion(),
    private val retryEnvironment: (String) -> Unit = {},
    private val hasEnvironmentFailure: (String) -> Boolean = { false },
) : JPanel(BorderLayout()) {
    private val model = DefaultListModel<DiscoveredProject>()
    private val list = RendererToolTipList(model)
    private val detailsPanel = ProjectDetailsPanel(
        currentProject,
        environmentDiscovery = environmentDiscovery,
        openSkill = openSkill,
        sessionActions = sessionActions,
    )
    private val retryLink = com.intellij.ui.components.ActionLink("Retry environment discovery") { list.selectedValue?.identity?.id?.let(retryEnvironment) }.apply { isVisible = false }
    private var updatingModel = false
    private var environmentLabels: Map<String, String> = emptyMap()
    private val responsive = ResponsiveMasterDetail(
        list = AgentHubUiComponents.alignedBorderlessScrollPane(list),
        details = detailsPanel,
        backButtonText = "Projects",
        hasSelection = { list.selectedValue != null },
        onBack = { list.requestFocusInWindow() },
        selectionTitle = { list.selectedValue?.let { DetailsTitle(it.name) } },
        splitProportion = splitProportion,
    )

    /** Invoked by the empty-list "Clear search" link; the tool window clears its shared search field. */
    var onClearSearch: () -> Unit = {}

    /** Fires while a narrow layout shows a project's details alone — the search box has nothing to narrow then. */
    var onDetailsOnlyChanged: (Boolean) -> Unit = {}

    init {
        responsive.onDetailsOnlyChanged = { onDetailsOnlyChanged(it) }
        list.cellRenderer = ProjectRenderer(agentName, ListHoverTracker(list)) { environmentLabels[it] }
        list.emptyText.text = "No projects discovered"
        ToolTipManager.sharedInstance().registerComponent(list)
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting && !updatingModel) {
                detailsPanel.setProject(list.selectedValue, list.selectedValue?.identity?.id?.let(environmentLabels::get))
                responsive.selectionChanged()
                retryLink.isVisible = list.selectedValue?.identity?.id?.let(hasEnvironmentFailure) == true
            }
        }
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "show-project-details")
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "show-project-details")
        list.actionMap.put("show-project-details", object : AbstractAction() {
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

    private fun contextMenu(project: DiscoveredProject): JPopupMenu = JPopupMenu().apply {
        val location = project.path ?: project.gitRoot ?: project.identity.canonicalPath
        add(JMenuItem("Show details").apply { addActionListener { responsive.showDetailsNow() } })
        add(JMenuItem("Open project").apply {
            isEnabled = !location.isNullOrBlank()
            addActionListener { detailsPanel.openSelectedProject() }
        })
        add(JMenuItem("Reveal project directory").apply {
            isEnabled = !location.isNullOrBlank()
            addActionListener { AgentHubFileActions.reveal(currentProject, location, "Reveal project", "Project directory is no longer available.") }
        })
        add(JMenuItem("Copy Path").apply {
            isEnabled = !location.isNullOrBlank()
            addActionListener { AgentHubFileActions.copyPath(location) }
        })
        if (hasEnvironmentFailure(project.identity.id)) {
            addSeparator()
            add(JMenuItem("Retry environment discovery").apply { addActionListener { retryEnvironment(project.identity.id) } })
        }
    }

    /** A fresh tab visit starts at the list and its first project. */
    fun showDefaultContent() {
        responsive.showList()
        if (model.size > 0) {
            list.selectedIndex = 0
            list.ensureIndexIsVisible(0)
        }
        detailsPanel.showDefaultTab()
    }
    fun setProjects(projects: List<DiscoveredProject>, refreshing: Boolean, filtered: Boolean = false) {
        val selectedId = list.selectedValue?.identity?.id
        updatingModel = true
        try {
            model.removeAllElements()
            projects.forEach(model::addElement)
            val selectedIndex = projects.indexOfFirst { it.identity.id == selectedId }
            list.selectedIndex = when {
                selectedIndex >= 0 -> selectedIndex
                projects.isNotEmpty() -> 0
                else -> -1
            }
        } finally {
            updatingModel = false
        }
        detailsPanel.setProject(list.selectedValue, list.selectedValue?.identity?.id?.let(environmentLabels::get))
        responsive.selectionChanged()
        retryLink.isVisible = list.selectedValue?.identity?.id?.let(hasEnvironmentFailure) == true
        list.emptyText.text = ProjectIndexUiModel.emptyText("projects", refreshing, filtered)
        if (filtered && !refreshing) {
            list.emptyText.appendLine("Clear search", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { onClearSearch() }
        }
    }

    fun setEnvironmentLabels(labels: Map<String, String>) {
        environmentLabels = labels
        retryLink.isVisible = list.selectedValue?.identity?.id?.let(hasEnvironmentFailure) == true
        // The optional environment line changes cell height after background discovery completes.
        if (model.size > 0) model.setElementAt(model.getElementAt(0), 0)
        list.revalidate()
        list.repaint()
        detailsPanel.setEnvironmentLabel(list.selectedValue?.identity?.id?.let(labels::get))
    }

    fun dispose() = detailsPanel.dispose()

    fun refreshDetails() = detailsPanel.setProject(list.selectedValue, list.selectedValue?.identity?.id?.let(environmentLabels::get))

    /** Selects the project with [projectId] (deep link from the Agents tab); returns whether it is listed. */
    fun selectProject(projectId: String): Boolean {
        val index = (0 until model.size).firstOrNull { model.getElementAt(it).identity.id == projectId } ?: return false
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
        responsive.showDetailsNow()
        return true
    }

    private class ProjectRenderer(
        private val agentName: (String) -> String,
        hover: ListHoverTracker,
        private val environmentLabel: (String) -> String?,
    ) : DetailRowRenderer<DiscoveredProject>(hover, maxDetailLines = 3) {
        private val iconsPanel = JPanel(
            FlowLayout(FlowLayout.LEFT, JBUI.scale(AgentHubUiComponents.SMALL_GAP), 0),
        ).apply { isOpaque = false }

        init {
            topRow.add(iconsPanel, BorderLayout.EAST)
        }

        override fun bind(value: DiscoveredProject) = DetailRow(
            title = value.name,
            titleAccessibleName = value.name,
            details = listOf(
                value.path?.let { "Path: $it" } ?: value.gitRemote?.let { "Git: $it" } ?: "Unknown location",
                AgentHubUiFormat.activitySummary("${value.agents.size} agents", value.agents.sumOf { it.sessionCount }, value.lastActivity),
                environmentLabel(value.identity.id).orEmpty(),
            ),
        )

        override fun bindTrailing(value: DiscoveredProject) {
            iconsPanel.removeAll()
            value.agents.forEach { relation ->
                val icon = AgentHubUiComponents.faviconFor(relation.agentId) ?: return@forEach
                iconsPanel.add(JLabel(icon).apply { toolTipText = agentName(relation.agentId); accessibleContext.accessibleName = toolTipText })
            }
        }
    }
}
