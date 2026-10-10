package com.shutterstar.agenthub.projects.ui

import com.intellij.icons.AllIcons

import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.AgentDetector
import com.shutterstar.agenthub.AgentSettingsState
import com.shutterstar.agenthub.DetectionResultsWatcher
import com.shutterstar.agenthub.TerminalCommandRunner
import com.shutterstar.agenthub.UserFacingError
import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.WslSupport
import com.shutterstar.agenthub.environment.config.discovery.ConfigDiscoveryService
import com.shutterstar.agenthub.environment.discovery.AgentEnvironmentDiscoveryService
import com.shutterstar.agenthub.environment.discovery.ProjectEnvironmentDiscoveryService
import com.shutterstar.agenthub.environment.instructions.discovery.InstructionDiscoveryService
import com.shutterstar.agenthub.environment.mcp.discovery.McpDiscoveryService
import com.shutterstar.agenthub.environment.model.ProjectEnvironment
import com.shutterstar.agenthub.environment.model.visibleTo
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexService
import com.shutterstar.agenthub.environment.persistence.SkillBrowserIndexService
import com.shutterstar.agenthub.environment.skills.discovery.SkillDiscoveryService
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.SkillSyncApplicationService
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillOwnershipStateService
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncAuditStateService
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncSettingsStateService
import com.shutterstar.agenthub.environment.skills.ui.SkillBrowserContext
import com.shutterstar.agenthub.environment.skills.ui.SkillBrowserController
import com.shutterstar.agenthub.environment.skills.ui.SkillBrowserDiscovery
import com.shutterstar.agenthub.environment.skills.ui.SkillBrowserModel
import com.shutterstar.agenthub.environment.skills.ui.SkillBrowserSnapshot
import com.shutterstar.agenthub.environment.skills.ui.SourceStat
import com.shutterstar.agenthub.environment.skills.ui.SkillBrowserState
import com.shutterstar.agenthub.environment.skills.ui.SkillMutationController
import com.shutterstar.agenthub.environment.skills.ui.SkillOccurrenceRow
import com.shutterstar.agenthub.environment.skills.sync.UndoAvailability
import com.shutterstar.agenthub.environment.skills.ui.SkillHistoryItem
import com.shutterstar.agenthub.environment.skills.ui.SkillSyncSettingsDialog
import com.shutterstar.agenthub.environment.skills.ui.SkillsPanel
import com.shutterstar.agenthub.environment.ui.EnvironmentUiModel
import com.shutterstar.agenthub.projects.launch.NativeResumeCommands
import com.shutterstar.agenthub.projects.model.AgentSession
import com.shutterstar.agenthub.projects.discovery.AgentProjectProviders
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import com.shutterstar.agenthub.projects.persistence.ProjectIndexService
import com.shutterstar.agenthub.storage.SharedStateChangeMonitor
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.GridBagLayout
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class AgentHubToolWindowPanel(
    private val project: Project,
    private val indexService: ProjectIndexService = ProjectIndexService.getInstance(),
) : JPanel(BorderLayout()), Disposable {
    // The search bar sits *below* the Projects/Agents/Skills tab strip (in LeftAlignedTabbedPane's
    // header slot), so the selected tab title itself says what is being searched — no separate
    // "Projects:" prefix label is needed any more.
    private val searchField = SearchTextField(false)
    private val refreshButton = JButton(AllIcons.Actions.Refresh)
    // Visible only while a Projects/Agents refresh is running — the status bar text alone sits
    // outside the tables' sightline and is easy to miss.
    private val refreshSpinner = AsyncProcessIcon("AgentHub tool window refresh").apply { isVisible = false }
    // Only agents whose CLI the latest detection found installed are shown anywhere in this window;
    // every discovery service below gets the same predicate so hidden providers never run.
    private val isAgentVisible: (String) -> Boolean = { AgentSettingsState.getInstance().isAgentVisible(it) }
    private val environmentDiscovery = ProjectEnvironmentDiscoveryService(
        skillDiscovery = SkillDiscoveryService(isAgentVisible = isAgentVisible),
        mcpDiscovery = McpDiscoveryService(isAgentVisible = isAgentVisible),
        instructionDiscovery = InstructionDiscoveryService(isAgentVisible = isAgentVisible),
        configDiscovery = ConfigDiscoveryService(isAgentVisible = isAgentVisible),
        persist = EnvironmentIndexService.getInstance()::record,
        isAgentVisible = isAgentVisible,
    )
    private val agentEnvironmentDiscovery = AgentEnvironmentDiscoveryService(environmentDiscovery, isAgentVisible = isAgentVisible)
    // One list/details divider position for the Projects, Agents and Skills tabs.
    private val splitProportion = SharedSplitProportion()
    private val sessionActions = SessionActions(
        resume = ::resumeSession,
        openTranscript = ::openTranscript,
        reveal = ::revealTranscript,
    )
    private val projectsPanel = ProjectsPanel(
        project,
        AgentHubUiComponents::displayName,
        environmentDiscovery,
        openSkill = ::navigateToSkill,
        sessionActions = sessionActions,
        splitProportion = splitProportion,
        retryEnvironment = ::retryProjectEnvironmentSummary,
        hasEnvironmentFailure = ::hasProjectEnvironmentFailure,
    )
    private val agentsPanel = AgentsPanel(
        project,
        environmentDiscovery,
        agentEnvironmentDiscovery,
        openSkill = ::navigateToSkill,
        openProject = ::navigateToProject,
        sessionActions = sessionActions,
        splitProportion = splitProportion,
        retryEnvironment = ::retryAgentEnvironmentSummary,
        hasEnvironmentFailure = ::hasAgentEnvironmentFailure,
    )
    private val skillSyncService = SkillSyncApplicationService.getInstance()
    private val skillsPanel: SkillsPanel = SkillsPanel(
        requestContext = { skillsController.refresh(it) },
        openSkill = { openSkillFile(it) },
        revealSkill = { revealSkillDirectory(it) },
        shareSkill = { skillMutationController.share(it) },
        startSharingSkill = { row, agentId -> skillMutationController.startSharing(row, agentId) },
        promoteSkill = { skillMutationController.promote(it) },
        resyncSkill = { row, agentId -> skillMutationController.resync(row, agentId) },
        stopSharingSkill = { row, agentId -> skillMutationController.stopSharing(row, agentId) },
        removeRedundantCopy = { row, agentId -> skillMutationController.removeRedundantCopy(row, agentId) },
        resolveVersions = { row, sides -> skillMutationController.resolveVersions(row, sides) },
        owningAgent = { path, candidates, scope, project ->
            runCatching { skillSyncService.owningAgentId(Path.of(path), candidates, scope, project) }.getOrNull()
        },
        owningAgentStrict = { path, candidates, scope, project ->
            runCatching { skillSyncService.owningAgentId(Path.of(path), candidates, scope, project, exactPrimaryOnly = true) }.getOrNull()
        },
        hasHistory = { row -> skillSyncService.historyFor(row.skill, row.context.scope, row.context.project).isNotEmpty() },
        historyEntries = { row ->
            val entries = skillSyncService.historyFor(row.skill, row.context.scope, row.context.project).take(HISTORY_PAGE_LIMIT)
            val availability = runCatching { skillSyncService.undoAvailability(entries.mapTo(mutableSetOf()) { it.operationId }) }
                .getOrDefault(UndoAvailability(emptySet(), emptySet()))
            entries.map { SkillHistoryItem(it, it.operationId in availability.undoable, it.operationId in availability.backupRemoved) }
        },
        undoOperation = { operationId -> skillMutationController.undoOperation(operationId) },
        restoreBackup = { row -> skillMutationController.restoreBackup(row) },
        retryFailedTargetIds = { row -> skillMutationController.failedTargetIds(row) },
        retryFailedTargets = { row -> skillMutationController.retryFailedTargets(row) },
        repairAllSkill = { row -> skillMutationController.repairAll(row) },
        managedTargetIds = { row -> skillSyncService.managedTargetIds(row.skill, row.context.scope, row.context.project) },
        isManagedOccurrence = { row ->
            row.source.agentId != null &&
                row.source.agentId in skillSyncService.managedTargetIds(row.skill, row.context.scope, row.context.project)
        },
        syncTargetIds = skillSyncService::adapterTargetIds,
        canReplaceCopyWithLink = skillSyncService::canReplaceCopyWithLink,
        openSettings = {
            val dialog = SkillSyncSettingsDialog(project, skillSyncService.currentSettings())
            if (dialog.showAndGet()) {
                dialog.result?.let(skillSyncService::updateSettings)
                // The Agents page offers Stop Sharing for existing targets only while the setting is on.
                skillsPanel.refresh()
            }
        },
        manageExistingTargets = { skillSyncService.currentSettings().manageExistingTargets },
        migrateDuplicates = { skills, context -> skillMutationController.migrateDuplicates(skills, context) },
        cancelBulkMigration = { skillMutationController.cancelBulkMigration() },
        failedBulkMigrationCount = { skillMutationController.failedBulkMigrationCount() },
        retryBulkFailures = { skills, context -> skillMutationController.retryBulkFailures(skills, context) },
        countMigratable = { skills, context -> skillMutationController.migratableCount(skills, context) },
        countRedundantCopies = { groups -> skillMutationController.redundantCopyCount(groups) },
        cleanUpRedundantCopies = { groups -> skillMutationController.cleanUpRedundantCopies(groups) },
        splitProportion = splitProportion,
    )
    private val skillsDiscovery = SkillBrowserDiscovery(
        discovery = SkillDiscoveryService(isAgentVisible = isAgentVisible),
        targetStatuses = { skill, context -> skillSyncService.targetStatuses(skill, context.scope, context.project) },
        backupSkillIds = { skills, context -> skillSyncService.skillIdsWithBackups(skills, context.scope, context.project) },
        projectsWithSkills = { skillProjectsWithSkills },
    )
    private val skillsController: SkillBrowserController = SkillBrowserController(
        executor = { ApplicationManager.getApplication().executeOnPooledThread(it) },
        deliver = { callback -> ApplicationManager.getApplication().invokeLater({
            if (!disposed && !project.isDisposed) callback()
        }, ModalityState.any()) },
        discover = { context ->
            skillsDiscovery.discover(context).also { snapshot ->
                if (snapshot.warnings.isEmpty()) {
                    runCatching { SkillBrowserIndexService.getInstance().record(
                        context.key,
                        snapshot.skills,
                        snapshot.sourceStats.filterValues { it.isLink }.keys,
                    ) }
                }
            }
        },
        cachedSnapshot = ::cachedSkillSnapshot,
        changed = ::onSkillsStateChanged,
    )
    private val skillMutationController = SkillMutationController(
        project = project,
        service = skillSyncService,
        executor = { ApplicationManager.getApplication().executeOnPooledThread(it) },
        deliver = { callback -> ApplicationManager.getApplication().invokeLater({
            if (!disposed && !project.isDisposed) callback()
        }, ModalityState.any()) },
        statusSink = ::showSkillsOperationStatus,
        notify = { action, message, type -> DetectionResultsWatcher.showNotification(project, action, message, type) },
        bulkStateChanged = skillsPanel::setBulkMigrationRunning,
        refresh = { selector ->
            environmentDiscovery.invalidateAll()
            projectsPanel.refreshDetails()
            agentsPanel.refreshDetails()
            updateEnvironmentSummaries()
            if (selector != null) skillsPanel.refreshPreferring(selector) else skillsPanel.refresh()
        },
    )
    private val tabs = LeftAlignedTabbedPane()
    private val toolbar = JPanel(BorderLayout(JBUI.scale(AgentHubUiComponents.CONTROL_GAP), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(AgentHubUiComponents.CONTROL_GAP, 0)
    }
    // Tabs currently showing one item's details alone in a narrow layout. Search/filter controls
    // only narrow the (hidden) list there, so the search box is hidden for those tabs (Refresh stays).
    private val detailsOnlyTabs = mutableSetOf<String>()
    private var activeTab = "Projects"
    private var switchingTabs = false
    private val statusLabel = AgentHubUiComponents.wrappingStatusText()
    private var projects: List<DiscoveredProject> = emptyList()
    private var refreshing = false
    private var disposed = false
    private val checkingSharedState = AtomicBoolean()
    private val sharedChanges = SharedStateChangeMonitor()
    private val lastSharedCheckNanos = AtomicLong(System.nanoTime() - SHARED_CHECK_MIN_INTERVAL_NANOS)
    private val sharedFocusListener = PropertyChangeListener { event ->
        val focused = event.newValue as? Component
        if (focused != null && SwingUtilities.isDescendingFrom(focused, this)) {
            refreshSharedDataIfChanged(throttle = true)
        }
    }

    /** [throttle]: focus moves inside the panel are frequent; explicit calls (open, Refresh) always check. */
    private fun refreshSharedDataIfChanged(throttle: Boolean = false) {
        if (disposed) return
        if (throttle) {
            val now = System.nanoTime()
            val last = lastSharedCheckNanos.get()
            if (now - last < SHARED_CHECK_MIN_INTERVAL_NANOS || !lastSharedCheckNanos.compareAndSet(last, now)) return
        } else {
            lastSharedCheckNanos.set(System.nanoTime())
        }
        if (!checkingSharedState.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val stamp = listOf(
                    indexService.storageStamp(),
                    EnvironmentIndexService.getInstance().storageStamp(),
                    SkillBrowserIndexService.getInstance().storageStamp(),
                    SkillOwnershipStateService.getInstance().storageStamp(),
                    SkillSyncAuditStateService.getInstance().storageStamp(),
                    SkillSyncSettingsStateService.getInstance().storageStamp(),
                ).joinToString("|")
                val changed = sharedChanges.observe(stamp)
                if (changed) ApplicationManager.getApplication().invokeLater({
                    if (!disposed && !project.isDisposed) {
                        environmentDiscovery.invalidateAll()
                        reloadFromCache()
                        skillsPanel.refresh()
                    }
                }, ModalityState.any())
            } finally {
                checkingSharedState.set(false)
            }
        }
    }
    private var skillsOperationStatus: String? = null
    private val skillsStatusClearTimer = javax.swing.Timer(SKILLS_STATUS_VISIBLE_MILLIS) { clearSkillsOperationStatus() }
        .apply { isRepeats = false }

    /**
     * Operation feedback sits on its own line above the summary. Problems and in-progress ("...") messages stay
     * until the next one replaces them; a plain success fades after a while (the notification balloon keeps it).
     */
    private fun showSkillsOperationStatus(message: String, sticky: Boolean) {
        skillsStatusClearTimer.stop()
        skillsOperationStatus = message
        if (!sticky && !message.endsWith("…")) skillsStatusClearTimer.start()
        render()
    }

    private fun clearSkillsOperationStatus() {
        if (disposed) return
        skillsOperationStatus = null
        render()
    }
    private val skillProjectScanGeneration = AtomicLong()
    private val environmentSummaryGeneration = AtomicLong()
    /** Set before the init block runs: the first scan there must be remembered. */
    private var environmentScanKey: EnvironmentScanKey? = null
    private var projectEnvironmentLabels: Map<String, String> = emptyMap()
    private var agentEnvironmentLabels: Map<String, String> = emptyMap()
    private val environmentSummaryResults = mutableMapOf<String, Result<ProjectEnvironment>>()
    private var environmentSummaryProjectsByAgent: Map<String, List<DiscoveredProject>> = emptyMap()
    /** Candidate list for the "all projects" skill-scope selection; kept current by [updateSkillProjects]. */
    private var skillProjectsWithSkills: List<DiscoveredProject> = emptyList()
    private val refreshSubscription = indexService.addRefreshListener {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) {
                // A refresh initiated elsewhere must also invalidate this window's detail caches - unless it
                // found the same projects and agents, in which case the environments are still current (they
                // also expire by themselves after a few minutes).
                if (!refreshing && environmentInputs(indexService.cachedProjects()) != environmentInputs(projects)) {
                    environmentDiscovery.invalidateAll()
                }
                reloadFromCache()
            }
        }
    }

    // Shown instead of the tabs while there is no completed detection for the current environment
    // (first run, environment switch, failed detection): no agent data is displayed unverified.
    private val checkingSpinner = AsyncProcessIcon("AgentHub installed agent detection")
    private val checkingLabel = JBLabel()
    private val checkingRetry = ActionLink("Detect installed agents") { retryDetection() }
    private val checkingPanel = JPanel(GridBagLayout()).apply {
        val column = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            checkingSpinner.alignmentX = Component.CENTER_ALIGNMENT
            checkingLabel.alignmentX = Component.CENTER_ALIGNMENT
            checkingRetry.alignmentX = Component.CENTER_ALIGNMENT
            add(checkingSpinner)
            add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))
            add(checkingLabel)
            add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))
            add(checkingRetry)
        }
        add(column)
    }
    private val cards = CardLayout()
    private val content = JPanel(cards)
    private val detectionSubscription = AgentSettingsState.getInstance().addDetectionListener {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed && !project.isDisposed) onInstalledAgentsChanged()
        }, ModalityState.any())
    }

    init {
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addPropertyChangeListener("permanentFocusOwner", sharedFocusListener)
        refreshSharedDataIfChanged()
        activePanelCount.incrementAndGet()
        border = JBUI.Borders.empty(AgentHubUiComponents.PANEL_INSET)
        searchField.textEditor.emptyText.text = "Search projects"
        searchField.textEditor.accessibleContext.accessibleName = "Search projects"
        refreshButton.toolTipText = "Refresh project index"
        refreshButton.accessibleContext.accessibleName = "Refresh project index"
        val refreshBox = JPanel(BorderLayout(JBUI.scale(AgentHubUiComponents.SMALL_GAP), 0)).apply {
            isOpaque = false
            add(refreshSpinner, BorderLayout.WEST)
            add(refreshButton, BorderLayout.EAST)
        }
        toolbar.add(searchField, BorderLayout.CENTER)
        toolbar.add(refreshBox, BorderLayout.EAST)

        tabs.addTab("Projects", projectsPanel)
        tabs.addTab("Agents", agentsPanel)
        tabs.addTab("Skills", skillsPanel)
        tabs.setHeader(toolbar)

        statusLabel.border = JBUI.Borders.emptyTop(AgentHubUiComponents.CONTROL_GAP)
        content.add(tabs, CARD_MAIN)
        content.add(checkingPanel, CARD_CHECKING)
        add(content, BorderLayout.CENTER)
        updateInstallationCard()
        add(statusLabel, BorderLayout.SOUTH)

        searchField.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) { if (!switchingTabs) render() }
            override fun removeUpdate(event: DocumentEvent) { if (!switchingTabs) render() }
            override fun changedUpdate(event: DocumentEvent) { if (!switchingTabs) render() }
        })
        refreshButton.addActionListener { refresh() }
        skillsPanel.onClearSearch = { searchField.text = "" }
        skillsPanel.onSummaryChanged = { if (activeTab == "Skills") updateSkillsStatus() }
        projectsPanel.onClearSearch = { searchField.text = "" }
        agentsPanel.onClearSearch = { searchField.text = "" }
        projectsPanel.onDetailsOnlyChanged = { setDetailsOnly("Projects", it) }
        agentsPanel.onDetailsOnlyChanged = { setDetailsOnly("Agents", it) }
        skillsPanel.onDetailsOnlyChanged = { setDetailsOnly("Skills", it) }
        tabs.addSelectionListener {
            activeTab = tabs.selectedTitle() ?: "Projects"
            switchingTabs = true
            try {
                searchField.text = ""
            } finally {
                switchingTabs = false
            }
            val label = when (activeTab) {
                "Skills" -> "Search skills"
                "Agents" -> "Search agents"
                else -> "Search projects"
            }
            searchField.textEditor.emptyText.text = label
            searchField.textEditor.accessibleContext.accessibleName = label
            refreshButton.toolTipText = if (activeTab == "Skills") "Refresh skills in this scope" else "Refresh project index"
            refreshButton.accessibleContext.accessibleName = refreshButton.toolTipText
            refreshButton.isEnabled = activeTab == "Skills" || !refreshing
            if (activeTab == "Skills") skillsPanel.showDefaultContent()
            render()
            when (activeTab) {
                "Skills" -> Unit
                "Agents" -> agentsPanel.showDefaultContent()
                else -> projectsPanel.showDefaultContent()
            }
            updateToolbarVisibility()
        }
        reloadFromCache()
        skillsPanel.refresh()
        if (!indexService.hasLiveResults()) refresh()
    }

    private fun updateInstallationCard() {
        val settings = AgentSettingsState.getInstance()
        val known = settings.isInstallationKnown()
        val failed = settings.detectionFailed
        checkingLabel.text = if (failed) "Could not detect installed agents." else "Checking installed agents…"
        checkingRetry.isVisible = failed
        checkingSpinner.isVisible = !failed
        if (known || failed) checkingSpinner.suspend() else checkingSpinner.resume()
        cards.show(content, if (known) CARD_MAIN else CARD_CHECKING)
        statusLabel.isVisible = known
    }

    private fun retryDetection() {
        checkingRetry.isVisible = false
        checkingSpinner.isVisible = true
        checkingSpinner.resume()
        checkingLabel.text = "Checking installed agents…"
        AgentDetector.detectAndNotify(project)
    }

    /**
     * The installed-agent set changed (startup detection, Detect, a Settings install/uninstall, a
     * WSL switch): drop everything derived from the old set and re-read it. The index itself is
     * re-discovered by [ProjectIndexService]'s own detection hook; until that lands, the filtered
     * cache already hides removed agents.
     */
    private fun onInstalledAgentsChanged() {
        environmentDiscovery.invalidateAll()
        updateInstallationCard()
        reloadFromCache()
        skillsPanel.refresh()
    }

    private fun setDetailsOnly(tab: String, detailsOnly: Boolean) {
        if (detailsOnly) detailsOnlyTabs += tab else detailsOnlyTabs -= tab
        updateToolbarVisibility()
    }

    private fun updateToolbarVisibility() {
        // Only the search box has nothing to narrow in a details-only layout; Refresh stays reachable there.
        searchField.isVisible = activeTab !in detailsOnlyTabs
    }

    private fun reloadFromCache() {
        projects = indexService.cachedProjects()
        updateSkillProjects()
        updateEnvironmentSummaries()
        render()
    }

    /** Installed agents without a single indexed session: still listed, because their environment exists. */
    private fun sessionlessAgentIds(): Set<String> {
        val withSessions = projects.flatMapTo(mutableSetOf()) { project -> project.agents.map { it.agentId } }
        return DISCOVERABLE_AGENT_IDS.filterTo(sortedSetOf()) { it !in withSessions && isAgentVisible(it) }
    }

    /** What the running/finished environment scan was started for; an identical request needs no new scan. */
    private data class EnvironmentScanKey(
        val cacheGeneration: Long,
        val projects: List<Triple<String, String?, Set<String>>>,
        val sessionlessAgents: Set<String>,
    )


    /** The parts of a project list its environment depends on (not session counts or activity times). */
    private fun environmentInputs(list: List<DiscoveredProject>): List<Triple<String, String?, Set<String>>> =
        list.map { Triple(it.identity.id, it.path, it.agents.mapTo(mutableSetOf()) { relation -> relation.agentId }) }

    private fun updateEnvironmentSummaries() {
        val candidates = projects.toList()
        val sessionless = sessionlessAgentIds()
        val scanKey = EnvironmentScanKey(environmentDiscovery.generation, environmentInputs(candidates), sessionless)
        // Same projects, same agents and no cache invalidation since the last scan: its results (or its
        // still-running scan) are current. Failures are retried explicitly, which bumps the generation.
        if (scanKey == environmentScanKey) {
            publishEnvironmentSummaryLabels()
            return
        }
        environmentScanKey = scanKey
        val ticket = environmentSummaryGeneration.incrementAndGet()
        environmentSummaryResults.clear()
        candidates.forEach { candidate ->
            val cached = EnvironmentIndexService.getInstance().cachedEnvironment(candidate.identity.id)
            if (cached != null) {
                val visible = cached.visibleTo(candidate.agents.mapTo(mutableSetOf()) { it.agentId })
                environmentSummaryResults[candidate.identity.id] = Result.success(visible)
            }
        }
        environmentSummaryProjectsByAgent = candidates.flatMap { candidate ->
            candidate.agents.map { relation -> relation.agentId to candidate }
        }.groupBy({ it.first }, { it.second })
        projectEnvironmentLabels = candidates.associate { candidate ->
            candidate.identity.id to (
                environmentSummaryResults[candidate.identity.id]?.getOrNull()
                    ?.let { EnvironmentUiModel.summaryLabel(EnvironmentUiModel.summary(it)) }
                    ?: SCANNING_ENVIRONMENT
            )
        }
        agentEnvironmentLabels = environmentSummaryProjectsByAgent.keys.associateWith(::agentEnvironmentLabel) +
            sessionless.associateWith { SCANNING_ENVIRONMENT }
        publishEnvironmentSummaryLabels()
        ApplicationManager.getApplication().executeOnPooledThread {
            candidates.forEach { candidate ->
                if (ticket != environmentSummaryGeneration.get()) return@executeOnPooledThread
                scanProjectEnvironmentSummary(candidate, ticket)
            }
            sessionless.forEach { agentId ->
                if (ticket != environmentSummaryGeneration.get()) return@executeOnPooledThread
                scanSessionlessAgentEnvironment(agentId, ticket)
            }
        }
    }

    /** An installed agent without sessions has no project to scan: its summary is its global environment. */
    private fun scanSessionlessAgentEnvironment(agentId: String, ticket: Long) {
        val result = runCatching { agentEnvironmentDiscovery.discover(agentId, emptyList()) }
        result.onFailure { error -> LOG.log(Level.WARNING, "Environment summary failed for agent $agentId", error) }
        ApplicationManager.getApplication().invokeLater({
            if (disposed || project.isDisposed || ticket != environmentSummaryGeneration.get()) return@invokeLater
            agentEnvironmentLabels = agentEnvironmentLabels + (
                agentId to result.fold(
                    onSuccess = { EnvironmentUiModel.agentSummaryLabel(EnvironmentUiModel.agentSummary(it), it.warnings.size) },
                    onFailure = { ENVIRONMENT_UNAVAILABLE },
                )
            )
            publishEnvironmentSummaryLabels()
        }, ModalityState.any())
    }

    private fun scanProjectEnvironmentSummary(candidate: DiscoveredProject, ticket: Long) {
        val result = runCatching { environmentDiscovery.discover(candidate) }
        result.onFailure { error ->
            LOG.log(Level.WARNING, "Environment summary failed for ${candidate.identity.id}", error)
        }
        ApplicationManager.getApplication().invokeLater({
            if (disposed || project.isDisposed || ticket != environmentSummaryGeneration.get()) return@invokeLater
            environmentSummaryResults[candidate.identity.id] = result
            projectEnvironmentLabels = projectEnvironmentLabels + (
                candidate.identity.id to result.fold(
                    onSuccess = { EnvironmentUiModel.summaryLabel(EnvironmentUiModel.summary(it)) },
                    onFailure = { ENVIRONMENT_UNAVAILABLE },
                )
            )
            candidate.agents.forEach { relation ->
                agentEnvironmentLabels = agentEnvironmentLabels + (relation.agentId to agentEnvironmentLabel(relation.agentId))
            }
            publishEnvironmentSummaryLabels()
        }, ModalityState.any())
    }

    private fun agentEnvironmentLabel(agentId: String): String {
        val related = environmentSummaryProjectsByAgent[agentId].orEmpty()
        val completed = related.mapNotNull { environmentSummaryResults[it.identity.id] }
        val successful = completed.mapNotNull(Result<ProjectEnvironment>::getOrNull)
        val failed = completed.count(Result<ProjectEnvironment>::isFailure)
        val pending = related.size - completed.size
        val summary = successful.takeIf { it.isNotEmpty() }?.let { environments ->
            val perProject = environments.map { environment -> environmentDiscovery.forAgent(environment, agentId) }
            val aggregate = agentEnvironmentDiscovery.aggregate(agentId, perProject)
            EnvironmentUiModel.agentSummaryLabel(EnvironmentUiModel.agentSummary(aggregate), aggregate.warnings.size)
        }?.takeUnless { it == "No environment items" && (pending > 0 || failed > 0) }
        return when {
            pending > 0 -> listOfNotNull(summary, "Scanning environment… ${completed.size}/${related.size} projects").joinToString(" · ")
            failed > 0 -> listOfNotNull(summary, "$failed project${if (failed == 1) "" else "s"} unavailable · Retry from the row menu or details")
                .joinToString(" · ")
            else -> summary ?: "No environment items"
        }
    }

    private fun publishEnvironmentSummaryLabels() {
        projectsPanel.setEnvironmentLabels(projectEnvironmentLabels)
        agentsPanel.setEnvironmentLabels(agentEnvironmentLabels)
    }

    private fun hasProjectEnvironmentFailure(projectId: String): Boolean =
        environmentSummaryResults[projectId]?.isFailure == true

    private fun hasAgentEnvironmentFailure(agentId: String): Boolean =
        environmentSummaryProjectsByAgent[agentId].orEmpty().any(::projectEnvironmentFailed)

    private fun projectEnvironmentFailed(candidate: DiscoveredProject): Boolean =
        hasProjectEnvironmentFailure(candidate.identity.id)

    private fun retryProjectEnvironmentSummary(projectId: String) {
        val candidate = projects.firstOrNull { it.identity.id == projectId } ?: return
        if (!hasProjectEnvironmentFailure(projectId)) return
        environmentDiscovery.invalidateAll()
        projectsPanel.refreshDetails()
        agentsPanel.refreshDetails()
        environmentSummaryResults.remove(projectId)
        projectEnvironmentLabels = projectEnvironmentLabels + (projectId to SCANNING_ENVIRONMENT)
        candidate.agents.forEach { relation ->
            agentEnvironmentLabels = agentEnvironmentLabels + (relation.agentId to agentEnvironmentLabel(relation.agentId))
        }
        publishEnvironmentSummaryLabels()
        val ticket = environmentSummaryGeneration.get()
        ApplicationManager.getApplication().executeOnPooledThread { scanProjectEnvironmentSummary(candidate, ticket) }
    }

    private fun retryAgentEnvironmentSummary(agentId: String) {
        environmentSummaryProjectsByAgent[agentId].orEmpty().filter(::projectEnvironmentFailed)
            .forEach { retryProjectEnvironmentSummary(it.identity.id) }
    }

    private fun updateSkillProjects() {
        val ticket = skillProjectScanGeneration.incrementAndGet()
        val path = project.basePath
        val current = path?.let {
            DiscoveredProject(ProjectIdentity("ide:$it", it, null, null), project.name, it, null, null, null, emptyList(), null)
        }
        val candidates = projects + listOfNotNull(current)
        val saved = EnvironmentIndexService.getInstance().cachedEnvironments()
        val cachedCandidates = candidates.filter { candidate ->
            saved[candidate.identity.id]?.skills?.any { skill ->
                skill.scope == SkillScope.PROJECT && skill.compatibleAgents.any(isAgentVisible)
            } == true
        }
        skillProjectsWithSkills = cachedCandidates
        skillsPanel.setProjects(cachedCandidates)
        ApplicationManager.getApplication().executeOnPooledThread {
            val withSkills = candidates.filter(skillsDiscovery::hasSkills)
            ApplicationManager.getApplication().invokeLater({
                if (!disposed && !project.isDisposed && ticket == skillProjectScanGeneration.get()) {
                    skillProjectsWithSkills = withSkills
                    skillsPanel.setProjects(withSkills)
                }
            }, ModalityState.any())
        }
    }

    /** Shows saved skill metadata while the live filesystem scan is running. */
    private fun cachedSkillSnapshot(context: SkillBrowserContext): SkillBrowserSnapshot? {
        val indexed = SkillBrowserIndexService.getInstance().cachedSkills(context.key)
        val savedEnvironments = EnvironmentIndexService.getInstance().cachedEnvironments()
        fun visibleSkills(skills: List<AgentSkill>) = skills.mapNotNull { skill ->
            val visible = skill.compatibleAgents.filterTo(mutableSetOf(), isAgentVisible)
            if (visible.isEmpty()) null else skill.copy(
                compatibleAgents = visible,
                sources = skill.sources.filter { it.agentId == null || isAgentVisible(it.agentId) },
            )
        }
        if (indexed.isNotEmpty() && (context.scope != SkillScope.PROJECT || context.project != null)) {
            return visibleSkills(indexed).takeIf { it.isNotEmpty() }?.let {
                // Link-ness is part of what the list looks like (links fold into their source), so it is cached too.
                val links = SkillBrowserIndexService.getInstance().cachedLinkPaths(context.key)
                SkillBrowserSnapshot(context, it, sourceStats = links.associateWith { SourceStat(0, 0L, isLink = true) })
            }
        }
        if (context.scope == SkillScope.GLOBAL) {
            val merged = savedEnvironments.values.flatMap { it.skills }
                .filter { it.scope == SkillScope.GLOBAL }
                .groupBy { it.identity.id }
                .values.map { copies ->
                    copies.first().copy(
                        sources = copies.flatMap { it.sources }.distinctBy { it.agentId to it.path },
                        compatibleAgents = copies.flatMapTo(mutableSetOf()) { it.compatibleAgents },
                    )
                }
            val skills = visibleSkills(merged)
            return skills.takeIf { it.isNotEmpty() }?.let {
                SkillBrowserSnapshot(context, it)
            }
        }
        val scoped = if (context.project != null) listOf(context.project) else projects
        val perProject = scoped.mapNotNull { candidate ->
            val skills = visibleSkills(savedEnvironments[candidate.identity.id]?.skills.orEmpty()
                .filter { it.scope == SkillScope.PROJECT })
            if (skills.isEmpty()) null else SkillBrowserContext(SkillScope.PROJECT, candidate) to skills
        }
        if (perProject.isEmpty()) return null
        if (context.project != null) {
            return SkillBrowserSnapshot(context, perProject.single().second)
        }
        return SkillBrowserSnapshot(
            context,
            perProject.flatMap { it.second },
            rows = perProject.flatMap { (projectContext, skills) ->
                SkillBrowserModel.rows(projectContext, skills)
            },
        )
    }

    private fun onSkillsStateChanged(state: SkillBrowserState) {
        skillsPanel.showState(state)
        if (activeTab != "Skills") return
        refreshSpinner.isVisible = state.loading
        if (state.loading) refreshSpinner.resume() else refreshSpinner.suspend()
        refreshButton.isEnabled = !state.loading
    }

    private fun refresh() {
        refreshSharedDataIfChanged()
        if (activeTab == "Skills") {
            skillsPanel.refresh()
            return
        }
        environmentDiscovery.invalidateAll()
        refreshing = true
        refreshButton.isEnabled = false
        refreshSpinner.isVisible = true
        refreshSpinner.resume()
        render()
        indexService.refreshInBackground().whenComplete { result, error ->
            ApplicationManager.getApplication().invokeLater {
                if (disposed) return@invokeLater
                refreshing = false
                refreshButton.isEnabled = true
                refreshSpinner.suspend()
                refreshSpinner.isVisible = false
                if (error == null) {
                    projects = indexService.cachedProjects()
                    updateSkillProjects()
                    updateEnvironmentSummaries()
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
        if (activeTab == "Skills") {
            skillsPanel.setQuery(query)
            updateSkillsStatus()
            return
        }
        val filteredProjects = ProjectIndexUiModel.filterProjects(projects, query, AgentHubUiComponents::displayName)
        val sessionless = sessionlessAgentIds()
        val agents = ProjectIndexUiModel.agents(projects, query, sessionless, AgentHubUiComponents::displayName)
        val filtered = query.isNotBlank()
        projectsPanel.setProjects(filteredProjects, refreshing, filtered)
        agentsPanel.setAgents(agents, projects, refreshing, filtered)

        val sessionCount = projects.sumOf { project -> project.agents.sumOf { it.sessionCount } }
        val agentCount = projects.flatMap { it.agents }.map { it.agentId }.toSet().size + sessionless.size
        val refreshedAt = indexService.lastRefreshedAt()?.let(AgentHubUiFormat.dateTime::format)
        val counts = if (activeTab == "Agents") {
            "${ProjectIndexUiModel.countLabel(agents.size, agentCount, "agents", filtered)} · ${projects.size} projects · $sessionCount sessions"
        } else {
            "${ProjectIndexUiModel.countLabel(filteredProjects.size, projects.size, "projects", filtered)} · $sessionCount sessions"
        }
        statusLabel.text = statusOverride ?: when {
            refreshing -> "Refreshing project index…"
            refreshedAt != null -> "$counts · Updated $refreshedAt"
            else -> "Project index has not been refreshed yet"
        }
    }

    private fun updateSkillsStatus() {
        val summary = skillsPanel.summaryText.ifBlank { "No skills discovered in this scope" }
        statusLabel.text = listOfNotNull(skillsOperationStatus, summary).joinToString("\n")
    }

    override fun dispose() {
        disposed = true
        skillsStatusClearTimer.stop()
        projectsPanel.dispose()
        agentsPanel.dispose()
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .removePropertyChangeListener("permanentFocusOwner", sharedFocusListener)
        skillProjectScanGeneration.incrementAndGet()
        environmentSummaryGeneration.incrementAndGet()
        refreshSpinner.dispose()
        skillsController.close()
        skillMutationController.close()
        refreshSubscription.close()
        detectionSubscription.close()
        checkingSpinner.dispose()
        if (activePanelCount.decrementAndGet() == 0) {
            indexService.cancelActiveRefresh()
        }
    }

    private fun openSkillFile(path: Path) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = runCatching {
                if (Files.isRegularFile(path)) LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) else null
            }.getOrNull()
            ApplicationManager.getApplication().invokeLater({
                if (disposed || project.isDisposed) return@invokeLater
                if (file != null) FileEditorManager.getInstance(project).openFile(file, true)
                else skillsPanel.showFileError("SKILL.md is no longer available. Refresh the skill list.")
            }, ModalityState.any())
        }
    }

    private fun revealSkillDirectory(path: Path) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val directory = runCatching { AgentHubFileActions.directoryToReveal(path) }.getOrNull()
            ApplicationManager.getApplication().invokeLater({
                if (disposed || project.isDisposed) return@invokeLater
                if (directory != null) AgentHubFileActions.revealDirectoryOrFile(directory)
                else skillsPanel.showFileError("The skill directory is no longer available. Refresh the skill list.")
            }, ModalityState.any())
        }
    }

    /**
     * Native resume (Milestone 5, minimal slice): reopen [session] with its own agent in a new IDE
     * terminal, in the session's project directory. The transcript file is re-checked first so a
     * session whose store was cleaned up since the last index refresh does not start a bare agent.
     */
    private fun resumeSession(session: AgentSession) {
        if (NativeResumeCommands.command(session.agentId, session.nativeResumeId) == null) return
        val settings = AgentSettingsState.getInstance()
        val snapshot = settings.executionSnapshot()
        ApplicationManager.getApplication().executeOnPooledThread {
            val transcriptMissing = session.sourcePath
                ?.let { path -> runCatching { !Files.exists(Path.of(path)) }.getOrDefault(true) }
                ?: false
            // Blocking `--help` probe of the installed CLI — decides which optional flags to pass.
            val command = NativeResumeCommands.command(session.agentId, session.nativeResumeId) { AgentDetector.shellOutput(it, executionSettings = snapshot.settings) }
                ?: return@executeOnPooledThread
            val workingDirectory = com.shutterstar.agenthub.projects.launch.SessionLaunchDirectory.resolve(session.projectPath)
            ApplicationManager.getApplication().invokeLater({
                if (disposed || project.isDisposed) return@invokeLater
                if (!settings.isExecutionCurrent(snapshot)) return@invokeLater
                if (transcriptMissing) {
                    DetectionResultsWatcher.showNotification(
                        project,
                        "Resume session",
                        "The session file is no longer available. Refresh the project index.",
                        NotificationType.WARNING,
                    )
                    return@invokeLater
                }
                if (workingDirectory == null) {
                    DetectionResultsWatcher.showNotification(
                        project, "Resume session",
                        "The session project directory is unavailable. Refresh the project index; the agent was not started.",
                        NotificationType.WARNING,
                    )
                    return@invokeLater
                }
                val agentName = AgentHubUiComponents.displayName(session.agentId)
                val name = (session.title ?: session.firstMessage)?.trim()?.takeIf { it.isNotEmpty() }
                val label = name?.let { if (it.length > TAB_TITLE_CHARS) it.take(TAB_TITLE_CHARS).trimEnd() + "…" else it }
                    ?: session.id.take(SESSION_ID_TITLE_CHARS)
                TerminalCommandRunner.run(project, "🤖 $agentName · $label", command, workingDirectory)
            }, ModalityState.any())
        }
    }

    private fun openTranscript(session: AgentSession) = AgentHubFileActions.open(
        project,
        session.sourcePath,
        "Open transcript",
        "The session file is no longer available. Refresh the project index.",
    ) { disposed }

    private fun revealTranscript(session: AgentSession) = AgentHubFileActions.reveal(
        project,
        session.sourcePath,
        "Reveal transcript",
        "The session file is no longer available. Refresh the project index.",
    ) { disposed }

    /** Deep link from the Agents tab's project list: show that project on the Projects tab. */
    private fun navigateToProject(projectId: String) {
        tabs.select("Projects")
        if (!projectsPanel.selectProject(projectId)) {
            // Filtered out by the Projects tab's own search text: clear it and retry once.
            searchField.text = ""
            render()
            projectsPanel.selectProject(projectId)
        }
    }

    private fun navigateToSkill(
        scope: SkillScope,
        skillId: String,
        sourcePath: String?,
        explicitProject: DiscoveredProject?,
    ) {
        val selectedProject = if (scope == SkillScope.PROJECT) {
            explicitProject ?: projects.firstOrNull { candidate ->
                val root = runCatching { Path.of(SkillBrowserContext.projectPath(candidate)).toAbsolutePath().normalize() }.getOrNull()
                val source = sourcePath?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
                root != null && source?.startsWith(root) == true
            }
        } else {
            null
        }
        if (scope == SkillScope.PROJECT && selectedProject == null) {
            skillsPanel.showFileError("The skill's project could not be resolved. Refresh the project index.")
            return
        }
        tabs.select("Skills")
        skillsPanel.navigateTo(SkillBrowserContext(scope, selectedProject), skillId, sourcePath)
    }

    companion object {
        private val LOG = Logger.getLogger(AgentHubToolWindowPanel::class.java.name)
        /** The History page lists this many newest operations of a skill (the audit trail itself is capped at 1000). */
        private const val HISTORY_PAGE_LIMIT = 50
        /** A plain success message in the Skills status line fades after this long. */
        private const val SKILLS_STATUS_VISIBLE_MILLIS = 15_000
        /** Agents the tool window can discover sessions for (one project provider each); static, as the init block already needs it. */
        private val DISCOVERABLE_AGENT_IDS: Set<String> get() = AgentProjectProviders.agentIds
        /** Focus changes inside the panel check the shared files for foreign changes at most this often. */
        private val SHARED_CHECK_MIN_INTERVAL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        private const val CARD_MAIN = "main"
        private const val CARD_CHECKING = "checking"
        private const val SCANNING_ENVIRONMENT = "Scanning environment…"
        private const val ENVIRONMENT_UNAVAILABLE = "Environment unavailable · Retry from the row menu or details"
        // ProjectIndexService is an app-level singleton shared across all open project windows;
        // only cancel its in-flight refresh once the last observing panel is gone.
        private val activePanelCount = AtomicInteger(0)
        private const val SESSION_ID_TITLE_CHARS = 8
        private const val TAB_TITLE_CHARS = 40
    }
}
