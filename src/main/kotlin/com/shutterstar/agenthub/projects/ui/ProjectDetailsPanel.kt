package com.shutterstar.agenthub.projects.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.concurrency.AppExecutorUtil
import com.shutterstar.agenthub.CodingAgent
import com.shutterstar.agenthub.CodingAgents
import com.shutterstar.agenthub.DetectionResultsWatcher
import com.shutterstar.agenthub.FaviconLoader
import com.shutterstar.agenthub.TerminalCommandRunner
import com.shutterstar.agenthub.environment.ui.EnvironmentPanel
import com.shutterstar.agenthub.environment.discovery.ProjectEnvironmentDiscoveryService
import com.shutterstar.agenthub.projects.ide.JetBrainsIdeDetector
import com.shutterstar.agenthub.projects.ide.JetBrainsIdeInstallation
import com.shutterstar.agenthub.projects.ide.JetBrainsIdeLauncher
import com.shutterstar.agenthub.projects.ide.ProjectIdeRecommendation
import com.shutterstar.agenthub.projects.launch.PendingAgentLaunchStore
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.resolve.ProjectResolver
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ProjectDetailsPanel(
    private val currentProject: Project,
    private val ideDetector: JetBrainsIdeDetector = JetBrainsIdeDetector(),
    environmentDiscovery: ProjectEnvironmentDiscoveryService = ProjectEnvironmentDiscoveryService(),
) : JPanel(BorderLayout()) {
    private val nameLabel = JBLabel("Select a project")
    private val pathLabel = JBLabel()
    private val gitLabel = JBLabel()
    private val branchLabel = JBLabel()
    private val activityLabel = JBLabel()
    private val agentsLabel = JBLabel()
    private val environmentPanel = EnvironmentPanel(currentProject, environmentDiscovery)
    private val ideModel = DefaultComboBoxModel<JetBrainsIdeInstallation>()
    private val ideCombo = JComboBox(ideModel)
    private val agentModel = DefaultComboBoxModel<CodingAgent>()
    private val agentCombo = JComboBox(agentModel)
    private val launchButton = JButton("Launch", AllIcons.Actions.Execute)
    private val openAndLaunchButton = JButton("Open & Launch", AllIcons.Actions.RunAll)
    private val actions = JPanel()
    private val pendingAgentLaunchStore = PendingAgentLaunchStore()
    private var selectedProject: DiscoveredProject? = null
    private var installedIdes: List<JetBrainsIdeInstallation> = emptyList()
    private var recommendedIde: JetBrainsIdeInstallation? = null

    init {
        border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
        nameLabel.font = nameLabel.font.deriveFont(Font.BOLD)
        listOf(pathLabel, gitLabel, branchLabel, activityLabel, agentsLabel).forEach { label ->
            label.foreground = JBColor.GRAY
        }

        val metadata = JPanel()
        metadata.layout = BoxLayout(metadata, BoxLayout.Y_AXIS)
        metadata.add(nameLabel)
        metadata.add(pathLabel)
        metadata.add(gitLabel)
        metadata.add(branchLabel)
        metadata.add(activityLabel)

        agentCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val agent = value as? CodingAgent
                text = agent?.name.orEmpty()
                icon = agent?.let(FaviconLoader::get)
                return component
            }
        }

        ideCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val installation = value as? JetBrainsIdeInstallation
                text = installation?.displayName.orEmpty() +
                    if (installation == recommendedIde) " (recommended)" else ""
                icon = AllIcons.Nodes.IdeaProject
                return component
            }
        }

        actions.layout = BoxLayout(actions, BoxLayout.X_AXIS)
        // Only the IDE combo may shrink (its content varies a lot in length); the agent combo's
        // width is fixed up front from the full known agent set so it never depends on which
        // project's agent subset happens to be loaded, and the launch buttons have static text.
        val agentComboMetrics = agentCombo.getFontMetrics(agentCombo.font)
        val agentComboWidth = (CodingAgents.all.maxOfOrNull { agentComboMetrics.stringWidth(it.name) } ?: 0) +
            JBUI.scale(AGENT_COMBO_CHROME_WIDTH)
        agentCombo.preferredSize = Dimension(agentComboWidth, agentCombo.preferredSize.height)
        ideCombo.minimumSize = Dimension(JBUI.scale(MIN_IDE_COMBO_WIDTH), ideCombo.preferredSize.height)
        listOf(agentCombo, launchButton, openAndLaunchButton).forEach { component ->
            component.maximumSize = component.preferredSize
            component.minimumSize = component.preferredSize
        }
        actions.add(ideCombo)
        actions.add(Box.createHorizontalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))
        actions.add(agentCombo)
        actions.add(Box.createHorizontalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))

        val overviewPanel = JPanel(BorderLayout())
        overviewPanel.border = JBUI.Borders.empty(
            AgentHubUiComponents.PANEL_INSET,
            AgentHubUiComponents.TEXT_LEFT_INSET,
            AgentHubUiComponents.PANEL_INSET,
            AgentHubUiComponents.PANEL_INSET,
        )
        overviewPanel.add(metadata, BorderLayout.CENTER)
        overviewPanel.add(actions, BorderLayout.SOUTH)

        val sessionsPanel = JPanel(BorderLayout())
        sessionsPanel.border = JBUI.Borders.empty(
            AgentHubUiComponents.PANEL_INSET,
            AgentHubUiComponents.TEXT_LEFT_INSET,
            AgentHubUiComponents.PANEL_INSET,
            AgentHubUiComponents.PANEL_INSET,
        )
        sessionsPanel.add(agentsLabel, BorderLayout.NORTH)

        val tabs = LeftAlignedTabbedPane()
        tabs.addTab("Overview", overviewPanel)
        tabs.addTab("Sessions", sessionsPanel)
        tabs.addTab("Environment", environmentPanel)
        add(tabs, BorderLayout.CENTER)

        launchButton.addActionListener { launchSelectedAgent() }
        openAndLaunchButton.addActionListener { openAndLaunchSelectedAgent() }
        ideCombo.addActionListener { refreshActionButton() }
        applyInstalledIdes(listOfNotNull(ideDetector.currentInstallation()))
        loadInstalledIdes()
        setProject(null)
    }

    fun setProject(project: DiscoveredProject?) {
        selectedProject = project
        agentModel.removeAllElements()
        project?.agents.orEmpty()
            .mapNotNull { relation -> CodingAgents.all.firstOrNull { it.id == relation.agentId } }
            .forEach(agentModel::addElement)

        val workingDirectory = project?.let(ProjectLaunchSupport::workingDirectory)
        nameLabel.text = project?.name ?: "Select a project"
        pathLabel.text = project?.path?.let { "Path: $it" }.orEmpty()
        gitLabel.text = project?.gitRemote?.let { "Git: $it" }.orEmpty()
        branchLabel.text = project?.currentBranch?.let { "Branch: $it" }.orEmpty()
        activityLabel.text = project?.lastActivity?.let { "Last activity: ${AgentHubUiFormat.dateTime.format(it)}" }.orEmpty()
        agentsLabel.text = project?.agents
            ?.joinToString(
                prefix = "<html><b>Agents</b><br>",
                separator = "<br>",
                postfix = "</html>",
            ) { relation ->
                val name = AgentHubUiComponents.displayName(relation.agentId)
                val lastActivity = relation.lastActivity?.let(AgentHubUiFormat.dateTime::format) ?: "Unknown"
                "$name: ${relation.sessionCount} sessions · Last activity: $lastActivity"
            }.orEmpty()
        ideCombo.isEnabled = workingDirectory != null && ideModel.size > 0
        agentCombo.isEnabled = workingDirectory != null && agentModel.size > 0
        launchButton.isEnabled = agentCombo.isEnabled
        openAndLaunchButton.isEnabled = agentCombo.isEnabled
        selectRecommendedIde(workingDirectory)
        refreshActionButton()
        environmentPanel.setProject(project)
    }

    private fun openSelectedProject() {
        val path = selectedProject?.let(ProjectLaunchSupport::workingDirectory) ?: return
        val installation = ideCombo.selectedItem as? JetBrainsIdeInstallation
        if (installation == null || installation.isCurrent) {
            ProjectUtil.openOrImport(path, currentProject, false)
            return
        }
        JetBrainsIdeLauncher.launch(installation, path).onFailure { error ->
            DetectionResultsWatcher.showNotification(
                currentProject,
                "Could not open ${installation.name}",
                error.message ?: "The IDE process could not be started.",
                NotificationType.ERROR,
            )
        }
    }

    private fun loadInstalledIdes() {
        AppExecutorUtil.getAppExecutorService().submit {
            val detected = ideDetector.detect()
            SwingUtilities.invokeLater {
                if (!currentProject.isDisposed) applyInstalledIdes(detected)
            }
        }
    }

    private fun applyInstalledIdes(installations: List<JetBrainsIdeInstallation>) {
        installedIdes = installations
        ideModel.removeAllElements()
        installations.forEach(ideModel::addElement)
        val workingDirectory = selectedProject?.let(ProjectLaunchSupport::workingDirectory)
        ideCombo.isEnabled = workingDirectory != null && ideModel.size > 0
        selectRecommendedIde(workingDirectory)
    }

    private fun selectRecommendedIde(workingDirectory: java.nio.file.Path?) {
        recommendedIde = workingDirectory?.let { ProjectIdeRecommendation.recommend(it, installedIdes) }
        ideCombo.selectedItem = recommendedIde ?: installedIdes.firstOrNull()
        ideCombo.repaint()
    }

    private fun launchSelectedAgent() {
        val project = selectedProject ?: return
        val workingDirectory = ProjectLaunchSupport.workingDirectory(project) ?: return
        val agent = agentCombo.selectedItem as? CodingAgent ?: return
        TerminalCommandRunner.run(
            currentProject,
            "🤖 ${agent.name} · ${project.name}",
            agent.command,
            workingDirectory.toString(),
        )
    }

    private fun openAndLaunchSelectedAgent() {
        val installation = ideCombo.selectedItem as? JetBrainsIdeInstallation
        val workingDirectory = selectedProject?.let(ProjectLaunchSupport::workingDirectory)
        val agent = agentCombo.selectedItem as? CodingAgent
        if (workingDirectory != null && agent != null) {
            // TerminalCommandRunner can only attach to a Project already loaded in this process.
            // Opening a not-yet-open project can land in a different window (or, for a different
            // installation, an entirely different OS process) than this one, so the launch is
            // handed off to whichever AgentHub instance ends up opening it — see
            // LlmBrainsStartupActivity / PendingAgentLaunchStore — rather than risking a terminal
            // opening in the wrong window.
            pendingAgentLaunchStore.request(workingDirectory, agent.id)
            if (installation != null && !installation.isCurrent) {
                DetectionResultsWatcher.showNotification(
                    currentProject,
                    "Opening ${installation.name}",
                    "${agent.name} will start automatically there once the project opens, " +
                        "if AgentHub is installed in ${installation.name}. Otherwise, launch it manually.",
                    NotificationType.INFORMATION,
                )
            }
        }
        openSelectedProject()
    }

    /** Whether [selectedProject] is already the open project in this IDE window/process. */
    private fun isSelectedProjectOpenInSelectedIde(): Boolean {
        val installation = ideCombo.selectedItem as? JetBrainsIdeInstallation ?: return false
        if (!installation.isCurrent) return false
        val workingDirectory = selectedProject?.let(ProjectLaunchSupport::workingDirectory) ?: return false
        val openBasePath = currentProject.basePath ?: return false
        val normalizedWorkingDirectory = ProjectResolver.normalizeFilesystemPath(workingDirectory.toString())
        val normalizedOpenPath = ProjectResolver.normalizeFilesystemPath(openBasePath)
        return normalizedWorkingDirectory != null && normalizedWorkingDirectory == normalizedOpenPath
    }

    private fun refreshActionButton() {
        actions.remove(launchButton)
        actions.remove(openAndLaunchButton)
        actions.add(if (isSelectedProjectOpenInSelectedIde()) launchButton else openAndLaunchButton)
        actions.revalidate()
        actions.repaint()
    }

    companion object {
        private const val MIN_IDE_COMBO_WIDTH = 90
        private const val AGENT_COMBO_CHROME_WIDTH = 48
    }
}
