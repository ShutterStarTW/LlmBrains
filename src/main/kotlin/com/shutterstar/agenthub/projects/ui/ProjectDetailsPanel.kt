package com.shutterstar.agenthub.projects.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.concurrency.AppExecutorUtil
import com.shutterstar.agenthub.CodingAgent
import com.shutterstar.agenthub.CodingAgents
import com.shutterstar.agenthub.DetectionResultsWatcher
import com.shutterstar.agenthub.FaviconLoader
import com.shutterstar.agenthub.TerminalCommandRunner
import com.shutterstar.agenthub.environment.discovery.ProjectEnvironmentDiscoveryService
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.ui.EnvironmentPanel
import com.shutterstar.agenthub.projects.ide.JetBrainsIdeDetector
import com.shutterstar.agenthub.projects.ide.JetBrainsIdeInstallation
import com.shutterstar.agenthub.projects.ide.JetBrainsIdeLauncher
import com.shutterstar.agenthub.projects.ide.ProjectIdeRecommendation
import com.shutterstar.agenthub.projects.launch.PendingAgentLaunchStore
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.resolve.ProjectResolver
import com.shutterstar.agenthub.projects.resolve.ProjectStats
import com.shutterstar.agenthub.projects.resolve.ProjectStatsCollector
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.LayoutManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingUtilities

internal class ProjectDetailsPanel(
    private val currentProject: Project,
    private val ideDetector: JetBrainsIdeDetector = JetBrainsIdeDetector(),
    private val environmentDiscovery: ProjectEnvironmentDiscoveryService = ProjectEnvironmentDiscoveryService(),
    openSkill: (SkillScope, String, String?, DiscoveredProject?) -> Unit = { _, _, _, _ -> },
    sessionActions: SessionActions = SessionActions(),
    private val collectStats: (String, String?) -> ProjectStats = { path, root -> ProjectStatsCollector.collect(path, root) },
    private val execute: (() -> Unit) -> Unit = { AppExecutorUtil.getAppExecutorService().submit(it) },
    private val deliver: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
) : JPanel(BorderLayout()) {
    // Name, location, activity and environment summary live above the tabs; the Overview tab
    // keeps the Git/size lines and launch controls.
    private val header = DetailsHeader()
    private val gitLabel = AgentHubUiComponents.wrappingStatusText()
    private val branchLabel = AgentHubUiComponents.wrappingStatusText()
    private val statsLabel = AgentHubUiComponents.wrappingStatusText().apply {
        foreground = JBColor.GRAY
        alignmentX = Component.LEFT_ALIGNMENT
    }
    private val sessionsPanel = SessionsPanel(actions = sessionActions)
    private val environmentPanel = EnvironmentPanel(
        currentProject,
        environmentDiscovery,
        openSkill = openSkill,
        execute = execute,
        deliverResult = deliver,
    )
    private val ideModel = DefaultComboBoxModel<JetBrainsIdeInstallation>()
    private val ideCombo = JComboBox(ideModel)
    private val agentModel = DefaultComboBoxModel<CodingAgent>()
    private val agentCombo = JComboBox(agentModel)
    private val launchButton = JButton("Launch", AllIcons.Actions.Execute)
    private val openAndLaunchButton = JButton("Open & Launch", AllIcons.Actions.RunAll)
    private val actions = JPanel(ResponsiveActionLayout())
    private val tabs = LeftAlignedTabbedPane()
    private val pendingAgentLaunchStore = PendingAgentLaunchStore()
    private var selectedProject: DiscoveredProject? = null
    private var environmentLabel: String? = null
    private var installedIdes: List<JetBrainsIdeInstallation> = emptyList()
    private var recommendedIde: JetBrainsIdeInstallation? = null
    private var statsGeneration = -1L
    private val statsCache = object : LinkedHashMap<Pair<String, String?>, ProjectStats>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String?>, ProjectStats>): Boolean = size > 32
    }
    private val statsInFlight = mutableSetOf<Pair<String, String?>>()
    private var disposed = false
    // Browsable https address of the Git remote; null for local/unknown remotes (label stays plain text).
    private var gitUrl: String? = null

    init {
        border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
        listOf(gitLabel, branchLabel, statsLabel).forEach { label ->
            label.foreground = JBColor.GRAY
            label.putClientProperty("html.disable", true)
        }

        val metadata = JPanel()
        metadata.layout = BoxLayout(metadata, BoxLayout.Y_AXIS)
        metadata.alignmentX = Component.LEFT_ALIGNMENT
        gitLabel.border = JBUI.Borders.emptyBottom(AgentHubUiComponents.CONTROL_GAP)
        gitLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(event)) gitUrl?.let(com.intellij.ide.BrowserUtil::browse)
            }
        })
        branchLabel.border = JBUI.Borders.emptyBottom(AgentHubUiComponents.CONTROL_GAP)
        metadata.add(gitLabel)
        metadata.add(branchLabel)
        metadata.add(statsLabel)

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

        // Keep the familiar horizontal controls while they fit; the layout stacks them only
        // when the available detail width falls below their combined minimum widths.
        ideCombo.minimumSize = Dimension(JBUI.scale(MIN_IDE_COMBO_WIDTH), ideCombo.preferredSize.height)
        ideCombo.maximumSize = Dimension(JBUI.scale(MAX_ACTION_CONTROL_WIDTH), ideCombo.preferredSize.height)
        listOf(launchButton, openAndLaunchButton).forEach { component ->
            component.maximumSize = component.preferredSize
            component.minimumSize = component.preferredSize
        }
        updateAgentComboWidth()
        actions.alignmentX = Component.LEFT_ALIGNMENT
        actions.add(ideCombo)
        actions.add(agentCombo)

        // Stacked directly under the metadata (not pinned to the panel's bottom edge, which — with
        // metadata in BorderLayout.CENTER — left a large dead gap between the info block and the
        // action row on any project with only a few metadata lines).
        val overviewContent = JPanel()
        overviewContent.layout = BoxLayout(overviewContent, BoxLayout.Y_AXIS)
        overviewContent.alignmentX = Component.LEFT_ALIGNMENT
        overviewContent.add(metadata)
        overviewContent.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))
        overviewContent.add(actions)

        val overviewPanel = JPanel(BorderLayout())
        overviewPanel.border = JBUI.Borders.empty(
            AgentHubUiComponents.PANEL_INSET,
            AgentHubUiComponents.TEXT_LEFT_INSET,
            AgentHubUiComponents.PANEL_INSET,
            AgentHubUiComponents.PANEL_INSET,
        )
        overviewPanel.add(overviewContent, BorderLayout.NORTH)
        overviewPanel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                actions.revalidate()
                overviewContent.revalidate()
            }
        })

        tabs.addTab("Overview", overviewPanel)
        tabs.addTab("Sessions", sessionsPanel)
        tabs.addTab("Environment", environmentPanel)
        add(header, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)

        launchButton.addActionListener { launchSelectedAgent() }
        openAndLaunchButton.addActionListener { openAndLaunchSelectedAgent() }
        ideCombo.addActionListener {
            ideCombo.toolTipText = (ideCombo.selectedItem as? JetBrainsIdeInstallation)?.displayName
            refreshActionButton()
        }
        applyInstalledIdes(listOfNotNull(ideDetector.currentInstallation()))
        loadInstalledIdes()
        setProject(null)
    }

    fun showDefaultTab() = tabs.selectFirst()

    fun setProject(project: DiscoveredProject?, environmentInfo: String? = null) {
        // A *different* project opens on Overview; a refresh of the same project keeps the tab
        // the user was reading (Sessions/Environment).
        if (disposed) return
        if (project == selectedProject && environmentInfo == environmentLabel && statsGeneration == environmentDiscovery.generation) return
        val sameProject = project?.identity?.id == selectedProject?.identity?.id
        val selectedAgentId = if (sameProject) (agentCombo.selectedItem as? CodingAgent)?.id else null
        val selectedIdeHome = if (sameProject) (ideCombo.selectedItem as? JetBrainsIdeInstallation)?.home else null
        if (!sameProject) tabs.selectFirst()
        selectedProject = project
        environmentLabel = environmentInfo
        agentModel.removeAllElements()
        project?.agents.orEmpty()
            .mapNotNull { relation -> CodingAgents.byId(relation.agentId) }
            .forEach(agentModel::addElement)
        (0 until agentModel.size).map(agentModel::getElementAt).firstOrNull { it.id == selectedAgentId }
            ?.let { agentCombo.selectedItem = it }
        updateAgentComboWidth()

        val workingDirectory = project?.let(ProjectLaunchSupport::workingDirectory)
        updateHeader()
        // The header already shows the path; only repeat the remote here when the path is shown
        // there (otherwise the remote *is* the header line).
        val gitRemote = project?.gitRemote?.takeIf { project.path != null }
        gitLabel.text = gitRemote?.let { "Git: $it" }.orEmpty()
        gitUrl = gitRemote?.let { webUrlOf(it) }
        gitLabel.foreground = if (gitUrl != null) JBUI.CurrentTheme.Link.Foreground.ENABLED else JBColor.GRAY
        gitLabel.cursor = Cursor.getPredefinedCursor(if (gitUrl != null) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
        gitLabel.isVisible = gitLabel.text.isNotEmpty()
        branchLabel.text = project?.currentBranch?.let { "Branch: $it" }.orEmpty()
        branchLabel.toolTipText = branchLabel.text.takeIf(String::isNotEmpty)
        branchLabel.isVisible = branchLabel.text.isNotEmpty()
        loadStats(project)
        val scope = project?.let { DetailsScope.ForProject(it) }
        sessionsPanel.setScope(scope)
        ideCombo.isEnabled = workingDirectory != null && ideModel.size > 0
        agentCombo.isEnabled = workingDirectory != null && agentModel.size > 0
        launchButton.isEnabled = agentCombo.isEnabled
        openAndLaunchButton.isEnabled = agentCombo.isEnabled
        selectRecommendedIde(workingDirectory)
        refreshActionButton()
        installedIdes.firstOrNull { it.home == selectedIdeHome }?.let { ideCombo.selectedItem = it }
        environmentPanel.setScope(scope)
    }

    fun setEnvironmentLabel(label: String?) {
        environmentLabel = label
        updateHeader()
    }

    private fun updateHeader() {
        val project = selectedProject
        header.set(
            project?.let { DetailsTitle(it.name) },
            project?.let {
                val activity = it.lastActivity?.let(AgentHubUiFormat.dateTime::format) ?: "Unknown"
                listOfNotNull(
                    it.path?.let { path -> "Path: $path" } ?: it.gitRemote?.let { remote -> "Git: $remote" },
                    "${it.agents.size} agents · ${it.agents.sumOf { agent -> agent.sessionCount }} sessions · Last activity: $activity",
                    environmentLabel,
                )
            }.orEmpty(),
            placeholder = "Select a project",
        )
    }

    // Walking the project directory (and, for a Git repo, counting commits) is too slow for the
    // EDT. Cache and in-flight requests are shared by path within each discovery generation;
    // delivery checks the current path, generation and disposal before updating the label.
    private fun loadStats(project: DiscoveredProject?) {
        val generation = environmentDiscovery.generation
        if (statsGeneration != generation) {
            statsGeneration = generation
            statsCache.clear()
            statsInFlight.clear()
        }
        val path = project?.path
        if (path == null) {
            statsLabel.text = ""
            statsLabel.toolTipText = null
            return
        }
        val key = path to project.gitRoot
        statsCache[key]?.let {
            statsLabel.text = formatStats(it)
            statsLabel.toolTipText = statsLabel.text
            return
        }
        if (!statsInFlight.add(key)) {
            statsLabel.text = "Calculating size…"
            statsLabel.toolTipText = null
            return
        }
        statsLabel.text = "Calculating size…"
        statsLabel.toolTipText = null
        execute {
            val result = runCatching { collectStats(path, project.gitRoot) }
            deliver {
                if (disposed || currentProject.isDisposed || environmentDiscovery.generation != generation) return@deliver
                statsInFlight.remove(key)
                result.getOrNull()?.let { statsCache[key] = it }
                if (selectedProject?.path == path && selectedProject?.gitRoot == project.gitRoot) {
                    statsLabel.text = result.fold(::formatStats, { "Statistics unavailable" })
                    statsLabel.toolTipText = statsLabel.text
                }
            }
        }
    }

    private fun formatStats(stats: ProjectStats): String {
        val filesPart = if (stats.fileCountTruncated) {
            "${stats.fileCount}+ files"
        } else {
            "${stats.fileCount} files"
        }
        val sizePart = AgentHubUiFormat.formatSize(stats.totalSizeBytes)
        val commitsPart = stats.commitCount?.let { "$it commits" }
        return listOfNotNull(filesPart, sizePart, commitsPart).joinToString("  ·  ")
    }

    // Sized from the *currently selected* project's own agents rather than the full ~40-agent
    // catalog, so a project with only one or two agents doesn't get a combo box padded out to fit
    // the longest name AgentHub knows about. Floored at MIN_AGENT_COMBO_LABEL so short names don't
    // look cramped.
    private fun updateAgentComboWidth() {
        val metrics = agentCombo.getFontMetrics(agentCombo.font)
        val widestName = (0 until agentModel.size).maxOfOrNull { metrics.stringWidth(agentModel.getElementAt(it).name) } ?: 0
        val width = maxOf(widestName, metrics.stringWidth(MIN_AGENT_COMBO_LABEL)) + JBUI.scale(AGENT_COMBO_CHROME_WIDTH)
        val size = Dimension(width, agentCombo.preferredSize.height)
        agentCombo.preferredSize = size
        agentCombo.minimumSize = Dimension(JBUI.scale(MIN_IDE_COMBO_WIDTH), size.height)
        agentCombo.maximumSize = Dimension(maxOf(width, JBUI.scale(MAX_ACTION_CONTROL_WIDTH)), size.height)
    }

    fun openSelectedProject() {
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
        execute {
            val detected = ideDetector.detect()
            deliver {
                if (!currentProject.isDisposed) applyInstalledIdes(detected)
            }
        }
    }

    private fun applyInstalledIdes(installations: List<JetBrainsIdeInstallation>) {
        if (disposed) return
        val selectedHome = (ideCombo.selectedItem as? JetBrainsIdeInstallation)?.home
        installedIdes = installations
        ideModel.removeAllElements()
        installations.forEach(ideModel::addElement)
        val workingDirectory = selectedProject?.let(ProjectLaunchSupport::workingDirectory)
        ideCombo.isEnabled = workingDirectory != null && ideModel.size > 0
        selectRecommendedIde(workingDirectory)
        installations.firstOrNull { it.home == selectedHome }?.let { ideCombo.selectedItem = it }
    }

    fun dispose() {
        disposed = true
        statsCache.clear()
        statsInFlight.clear()
        environmentPanel.dispose()
    }

    private fun selectRecommendedIde(workingDirectory: java.nio.file.Path?) {
        recommendedIde = workingDirectory?.let { ProjectIdeRecommendation.recommend(it, installedIdes) }
        ideCombo.selectedItem = recommendedIde ?: installedIdes.firstOrNull()
        ideCombo.toolTipText = (ideCombo.selectedItem as? JetBrainsIdeInstallation)?.displayName
        ideCombo.repaint()
    }

    private fun launchSelectedAgent() {
        val project = selectedProject ?: return
        val workingDirectory = ProjectLaunchSupport.workingDirectory(project) ?: return
        val agent = agentCombo.selectedItem as? CodingAgent ?: return
        TerminalCommandRunner.runAgent(
            currentProject,
            "🤖 ${agent.name} · ${project.name}",
            agent,
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

    private class ResponsiveActionLayout : LayoutManager {
        override fun addLayoutComponent(name: String?, component: Component?) = Unit

        override fun removeLayoutComponent(component: Component?) = Unit

        override fun preferredLayoutSize(parent: Container): Dimension {
            val controls = parent.components.filter(Component::isVisible)
            if (controls.isEmpty()) return Dimension(0, 0)
            val gap = JBUI.scale(AgentHubUiComponents.CONTROL_GAP)
            val widths = controls.map { minOf(it.preferredSize.width, it.maximumSize.width) }
            val heights = controls.map { it.preferredSize.height }
            val available = parent.parent?.width?.takeIf { it > 0 } ?: parent.width.takeIf { it > 0 }
            val minimumRowWidth = controls.indices.sumOf { minOf(controls[it].minimumSize.width, widths[it]) } +
                gap * (controls.size - 1)
            return if (available != null && available < minimumRowWidth) {
                Dimension(available, heights.sum() + gap * (controls.size - 1))
            } else {
                Dimension(widths.sum() + gap * (controls.size - 1), heights.max())
            }
        }

        override fun minimumLayoutSize(parent: Container): Dimension = Dimension(0, preferredLayoutSize(parent).height)

        override fun layoutContainer(parent: Container) {
            val controls = parent.components.filter(Component::isVisible)
            if (controls.isEmpty()) return
            val gap = JBUI.scale(AgentHubUiComponents.CONTROL_GAP)
            val widths = controls.map { minOf(it.preferredSize.width, it.maximumSize.width) }.toMutableList()
            val minimumWidths = controls.indices.map { minOf(controls[it].minimumSize.width, widths[it]) }
            val minimumRowWidth = minimumWidths.sum() + gap * (controls.size - 1)
            if (parent.width < minimumRowWidth) {
                var y = 0
                controls.forEachIndexed { index, control ->
                    val height = control.preferredSize.height
                    control.setBounds(0, y, minOf(widths[index], parent.width), height)
                    y += height + gap
                }
                return
            }
            var overflow = (widths.sum() + gap * (controls.size - 1) - parent.width).coerceAtLeast(0)
            controls.indices.forEach { index ->
                val reduction = minOf(overflow, widths[index] - minimumWidths[index])
                widths[index] -= reduction
                overflow -= reduction
            }
            var x = 0
            controls.forEachIndexed { index, control ->
                control.setBounds(x, 0, widths[index], control.preferredSize.height)
                x += widths[index] + gap
            }
        }
    }

    companion object {
        /** `host/owner/repo` (the normalized remote) as an https address; null for local paths. */
        internal fun webUrlOf(remote: String): String? {
            if ('.' !in remote.substringBefore('/') || remote.startsWith("/")) return null
            return "https://$remote".takeIf { runCatching { java.net.URI(it).host }.getOrNull() != null }
        }

        private const val MIN_IDE_COMBO_WIDTH = 90
        private const val MAX_ACTION_CONTROL_WIDTH = 300
        private const val AGENT_COMBO_CHROME_WIDTH = 48
        private const val MIN_AGENT_COMBO_LABEL = "Claude Code"
    }
}
