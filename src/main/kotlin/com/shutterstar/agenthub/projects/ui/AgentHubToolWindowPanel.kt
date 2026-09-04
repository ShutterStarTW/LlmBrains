package com.shutterstar.agenthub.projects.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.environment.discovery.AgentEnvironmentDiscoveryService
import com.shutterstar.agenthub.environment.discovery.ProjectEnvironmentDiscoveryService
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexService
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.persistence.ProjectIndexService
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class AgentHubToolWindowPanel(
    private val project: Project,
    private val indexService: ProjectIndexService = ProjectIndexService.getInstance(),
) : JPanel(BorderLayout()), Disposable {
    private val searchField = SearchTextField(false)
    private val refreshButton = JButton(AllIcons.Actions.Refresh)
    private val environmentDiscovery = ProjectEnvironmentDiscoveryService(
        persist = EnvironmentIndexService.getInstance()::record,
    )
    private val projectsPanel = ProjectsPanel(project, AgentHubUiComponents::displayName, environmentDiscovery)
    private val agentsPanel = AgentsPanel(project, AgentEnvironmentDiscoveryService(environmentDiscovery))
    private val statusLabel = JBLabel()
    private var projects: List<DiscoveredProject> = emptyList()
    private var refreshing = false
    private var disposed = false
    private val refreshSubscription = indexService.addRefreshListener {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) reloadFromCache()
        }
    }

    init {
        activePanelCount.incrementAndGet()
        border = JBUI.Borders.empty(AgentHubUiComponents.PANEL_INSET)
        val toolbar = JPanel(BorderLayout(JBUI.scale(AgentHubUiComponents.CONTROL_GAP), 0))
        searchField.textEditor.emptyText.text = "Search projects and agents"
        searchField.textEditor.accessibleContext.accessibleName = "Search projects and agents"
        refreshButton.toolTipText = "Refresh project index"
        refreshButton.accessibleContext.accessibleName = "Refresh project index"
        toolbar.add(searchField, BorderLayout.CENTER)
        toolbar.add(refreshButton, BorderLayout.EAST)

        val tabs = LeftAlignedTabbedPane()
        tabs.addTab("Projects", projectsPanel)
        tabs.addTab("Agents", agentsPanel)

        statusLabel.border = JBUI.Borders.emptyTop(AgentHubUiComponents.CONTROL_GAP)
        add(toolbar, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        searchField.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = render()
            override fun removeUpdate(event: DocumentEvent) = render()
            override fun changedUpdate(event: DocumentEvent) = render()
        })
        refreshButton.addActionListener { refresh() }

        reloadFromCache()
        if (indexService.lastRefreshedAt() == null) refresh()
    }

    private fun reloadFromCache() {
        projects = indexService.cachedProjects()
        render()
    }

    private fun refresh() {
        environmentDiscovery.invalidateAll()
        refreshing = true
        refreshButton.isEnabled = false
        render()
        indexService.refreshInBackground().whenComplete { result, error ->
            ApplicationManager.getApplication().invokeLater {
                if (disposed) return@invokeLater
                refreshing = false
                refreshButton.isEnabled = true
                if (error == null) {
                    projects = indexService.cachedProjects()
                    val warningStatus = result.warnings.takeIf { it.isNotEmpty() }?.joinToString(", ") { warning ->
                        "${AgentHubUiComponents.displayName(warning.agentId)}: ${warning.message}"
                    }
                    render(warningStatus?.let { "Updated with warnings · $it" })
                } else {
                    render("Project discovery failed; showing the cached index")
                }
            }
        }
    }

    private fun render(statusOverride: String? = null) {
        val query = searchField.text
        val filteredProjects = ProjectIndexUiModel.filterProjects(projects, query, AgentHubUiComponents::displayName)
        val agents = ProjectIndexUiModel.agents(projects, query, AgentHubUiComponents::displayName)
        projectsPanel.setProjects(filteredProjects, refreshing)
        agentsPanel.setAgents(agents, projects, refreshing)

        val sessionCount = projects.sumOf { project -> project.agents.sumOf { it.sessionCount } }
        val refreshedAt = indexService.lastRefreshedAt()?.let(AgentHubUiFormat.dateTime::format)
        statusLabel.text = statusOverride ?: when {
            refreshing -> "Refreshing project index…"
            refreshedAt != null -> "${projects.size} projects · $sessionCount sessions · Updated $refreshedAt"
            else -> "Project index has not been refreshed yet"
        }
    }

    override fun dispose() {
        disposed = true
        refreshSubscription.close()
        if (activePanelCount.decrementAndGet() == 0) {
            indexService.cancelActiveRefresh()
        }
    }

    companion object {
        // ProjectIndexService is an app-level singleton shared across all open project windows;
        // only cancel its in-flight refresh once the last observing panel is gone.
        private val activePanelCount = AtomicInteger(0)
    }
}
