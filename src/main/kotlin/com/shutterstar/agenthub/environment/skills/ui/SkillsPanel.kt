package com.shutterstar.agenthub.environment.skills.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.environment.capabilities.AgentCapabilityRegistry
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.environment.skills.sync.migration.BulkMigrationDetector
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOwner
import com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.ui.AgentHubUiComponents
import com.shutterstar.agenthub.projects.ui.AgentHubUiFormat
import com.shutterstar.agenthub.projects.ui.CollapsibleWarningBar
import com.shutterstar.agenthub.projects.ui.DetailsHeader
import com.shutterstar.agenthub.projects.ui.DetailsTitle
import com.shutterstar.agenthub.projects.ui.LeftAlignedTabbedPane
import com.shutterstar.agenthub.projects.ui.ListHoverTracker
import com.shutterstar.agenthub.projects.ui.RendererToolTipList
import com.shutterstar.agenthub.projects.ui.ResponsiveMasterDetail
import com.shutterstar.agenthub.projects.ui.RoundedSelectionPanel
import com.shutterstar.agenthub.projects.ui.SharedSplitProportion
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButton
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.UIManager
import javax.swing.text.DefaultCaret

/** First Skills UI increment: inspect actual sources; all file mutations remain in the sync layer. */
internal class SkillsPanel(
    private val requestContext: (SkillBrowserContext) -> Unit,
    private val openSkill: (Path) -> Unit,
    private val revealSkill: (Path) -> Unit,
    private val shareSkill: (SkillOccurrenceRow) -> Unit = {},
    private val startSharingSkill: (SkillOccurrenceRow, String) -> Unit = { _, _ -> },
    private val promoteSkill: (SkillOccurrenceRow) -> Unit = {},
    private val resyncSkill: (SkillOccurrenceRow, String) -> Unit = { _, _ -> },
    private val stopSharingSkill: (SkillOccurrenceRow, String) -> Unit = { _, _ -> },
    /** Removes an agent's own link/copy of the shared skill (reviewed plan, backed up first). */
    private val removeRedundantCopy: (SkillOccurrenceRow, String) -> Unit = { _, _ -> },
    /** Opens the one comparison dialog: the row's skill against another version (see [versionSides]). */
    private val resolveVersions: (SkillOccurrenceRow, VersionSides) -> Unit = { _, _ -> },
    /** Which candidate agent really owns the skill directory at a path (agents read each other's folders); null = unknown. */
    private val owningAgent: (path: String, candidateAgentIds: List<String>, scope: SkillScope, project: DiscoveredProject?) -> String? =
        { _, candidates, _, _ -> candidates.firstOrNull() },
    /** Like [owningAgent] but only the agent whose own skill directory it is (what the clean-up uses to tell redundant copies). */
    private val owningAgentStrict: (path: String, candidateAgentIds: List<String>, scope: SkillScope, project: DiscoveredProject?) -> String? =
        { _, candidates, _, _ -> candidates.firstOrNull() },
    private val hasHistory: (SkillOccurrenceRow) -> Boolean = { false },
    /** Every recorded operation for the row's skill, newest first (the History page lists them all). */
    private val historyEntries: (SkillOccurrenceRow) -> List<SkillHistoryItem> = { emptyList() },
    /** Previews and reverses one past operation by id (the History page's Undo button). */
    private val undoOperation: (String) -> Unit = {},
    private val restoreBackup: (SkillOccurrenceRow) -> Unit = {},
    private val hasBackups: (SkillOccurrenceRow) -> Boolean = { false },
    private val retryFailedTargetIds: (SkillOccurrenceRow) -> Set<String> = { emptySet() },
    private val retryFailedTargets: (SkillOccurrenceRow) -> Unit = {},
    private val repairAllSkill: (SkillOccurrenceRow) -> Unit = {},
    private val managedTargetIds: (SkillOccurrenceRow) -> Set<String> = { emptySet() },
    private val isManagedOccurrence: (SkillOccurrenceRow) -> Boolean = { false },
    private val syncTargetIds: () -> Set<String> = { emptySet() },
    /** True when an agent's identical copy can be swapped for a link (it has a linking adapter and links are the preferred mode). */
    private val canReplaceCopyWithLink: (String) -> Boolean = { true },
    private val openSettings: () -> Unit = {},
    private val migrateDuplicates: (List<AgentSkill>, SkillBrowserContext) -> Unit = { _, _ -> },
    private val cancelBulkMigration: () -> Unit = {},
    private val failedBulkMigrationCount: () -> Int = { 0 },
    private val retryBulkFailures: (List<AgentSkill>, SkillBrowserContext) -> Unit = { _, _ -> },
    /** How many skills "Migrate duplicates" would list (installed agents only); the default ignores who is installed. */
    private val countMigratable: (List<AgentSkill>, SkillBrowserContext) -> Int = { skills, _ -> BulkMigrationDetector().detect(skills).size },
    private val countRedundantCopies: (List<SkillGroup>) -> Int = { 0 },
    private val cleanUpRedundantCopies: (List<SkillGroup>) -> Unit = {},
    private val displayName: (String) -> String = AgentHubUiComponents::displayName,
    private val agentIcon: (String) -> Icon? = AgentHubUiComponents::faviconFor,
    /** True while the "Manage existing skills" setting is on: links/copies AgentHub did not create can be stopped too. */
    private val manageExistingTargets: () -> Boolean = { false },
    splitProportion: SharedSplitProportion = SharedSplitProportion(),
) : JPanel(BorderLayout()) {
    private val scope = JComboBox(SkillScope.entries.toTypedArray())
    private val projectChoice = JComboBox<DiscoveredProject>()
    // Filter state lives in plain fields; the Filters popup is built from it on demand, so only
    // the button (with an active-filter count) takes header space.
    private var stateFilter = SkillBrowserFilter.ALL
    private var ownershipFilter = SkillOwnershipFilter.ALL
    private var agentFilter: String? = null
    private val filtersButton = JButton("Filters \u25be")
    private val settingsButton = JButton(AllIcons.General.Settings).apply {
        toolTipText = "Skill sync settings"
        accessibleContext.accessibleName = "Skill sync settings"
    }
    private val bulkMigrationButton = JButton("Migrate duplicates…").apply {
        toolTipText = "Review identical copies before promoting and sharing them"
        accessibleContext.accessibleName = "Migrate duplicate skills"
        isVisible = false
    }
    private val redundantCopyButton = JButton("Clean up redundant copies…").apply {
        toolTipText = "Remove the agents' own links or identical copies of shared skills they already read directly"
        accessibleContext.accessibleName = "Clean up redundant skill copies"
        isVisible = false
    }
    private val errorText = textArea("")
    private val warningBar = CollapsibleWarningBar()
    // GridBagLayout shrinks the buttons toward their minimum size on a narrow panel (their text then gets
    // an ellipsis) instead of clipping it like a FlowLayout would.
    private val migrationRow = JPanel(java.awt.GridBagLayout()).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, AgentHubUiComponents.TEXT_LEFT_INSET, AgentHubUiComponents.CONTROL_GAP, 0)
        bulkMigrationButton.minimumSize = Dimension(JBUI.scale(96), bulkMigrationButton.preferredSize.height)
        add(
            bulkMigrationButton,
            GridBagConstraints().apply { anchor = GridBagConstraints.WEST; gridx = 0; insets = JBUI.insetsRight(AgentHubUiComponents.CONTROL_GAP) },
        )
        redundantCopyButton.minimumSize = Dimension(JBUI.scale(96), redundantCopyButton.preferredSize.height)
        add(
            redundantCopyButton,
            GridBagConstraints().apply { anchor = GridBagConstraints.WEST; gridx = 1; weightx = 1.0 },
        )
        isVisible = false
    }
    private val listModel = DefaultListModel<SkillOccurrenceRow>()
    private val list = RendererToolTipList(listModel)
    private val hover = ListHoverTracker(list)
    private val listPage = JPanel(BorderLayout())
    private val listContent = JPanel(BorderLayout())
    private val listScrollPane = AgentHubUiComponents.alignedBorderlessScrollPane(list)
    private val details = JPanel(BorderLayout())
    private val empty = JPanel(BorderLayout())
    private val emptyText = JBLabel("Discovering skills…")
    private val clearFilters = JButton("Clear filters")
    private val filterSummary = AgentHubUiComponents.wrappingStatusText()
    private val filterStatus = AgentHubUiComponents.filterStatusRow(filterSummary, clearFilters)
    private val responsive = ResponsiveMasterDetail(
        list = listPage,
        details = details,
        backButtonText = "Skills",
        hasSelection = { selectedOccurrence != null },
        onBack = ::returnToList,
        selectionTitle = { selectedOccurrence?.let { DetailsTitle(it.title) } },
        splitProportion = splitProportion,
    )
    private val detailsHeader = DetailsHeader()
    private val detailTabs = LeftAlignedTabbedPane()
    private var shownRowKey: String? = null
    private var snapshot: SkillBrowserSnapshot? = null
    private var allRows: List<SkillOccurrenceRow> = emptyList()
    private var query = ""
    private var loading = false
    private var error: String? = null
    private var updatingControls = false
    private var updatingList = false
    private var lastRequestedContext: String? = null
    private var pendingSelector: ((SkillOccurrenceRow) -> Boolean)? = null
    private var pendingNavigation = false
    private var bulkRunning = false

    /** Fires while a narrow layout shows a skill's details alone; the host hides its search box. */
    var onDetailsOnlyChanged: (Boolean) -> Unit = {}
    var onSummaryChanged: (String) -> Unit = {}
    var summaryText: String = ""
        private set

    val selectedOccurrence: SkillOccurrenceRow? get() = list.selectedValue
    val occurrenceCount: Int get() = listModel.size()

    /** A fresh tab visit starts at the global list with its default filters and first row. */
    fun showDefaultContent() {
        pendingSelector = null
        pendingNavigation = false
        updatingControls = true
        scope.selectedItem = SkillScope.GLOBAL
        if (projectChoice.itemCount > 0) projectChoice.selectedIndex = 0
        updatingControls = false
        resetFilters()
        query = ""
        responsive.showList()
        contextChanged()
        renderRows()
        if (listModel.size() > 0) {
            list.selectedIndex = 0
            list.ensureIndexIsVisible(0)
        }
        detailTabs.selectFirst()
    }

    init {
        scope.accessibleContext.accessibleName = "Skill scope"
        scope.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component =
                super.getListCellRendererComponent(list, if (value == SkillScope.PROJECT) "Project" else "Global", index, selected, focus)
        }
        projectChoice.accessibleContext.accessibleName = "Skill project"
        projectChoice.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component {
                val item = value as? DiscoveredProject
                return super.getListCellRendererComponent(list, item?.name ?: "All projects", index, selected, focus).also {
                    putClientProperty("html.disable", true)
                    toolTipText = item?.let(SkillBrowserContext::projectPath)
                }
            }
        }
        filtersButton.accessibleContext.accessibleName = "Skill filters"
        filtersButton.addActionListener { showFiltersPopup() }
        // One row of controls (scope / project / filters / settings); the migration button, error
        // text and warning bar only take space while they have something to say.
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyTop(AgentHubUiComponents.PANEL_INSET)
        }
        val contextBar = JPanel(BorderLayout(JBUI.scale(AgentHubUiComponents.CONTROL_GAP), 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, AgentHubUiComponents.TEXT_LEFT_INSET, AgentHubUiComponents.CONTROL_GAP, 0)
            add(scope, BorderLayout.WEST)
            add(projectChoice, BorderLayout.CENTER)
            val trailing = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(AgentHubUiComponents.SMALL_GAP), 0))
            trailing.add(filtersButton)
            trailing.add(settingsButton)
            add(trailing, BorderLayout.EAST)
        }
        errorText.border = JBUI.Borders.empty(0, AgentHubUiComponents.TEXT_LEFT_INSET, AgentHubUiComponents.CONTROL_GAP, 0)
        errorText.alignmentX = Component.LEFT_ALIGNMENT
        errorText.isVisible = false
        header.add(contextBar)
        header.add(migrationRow)
        header.add(errorText)
        header.add(warningBar)
        header.add(filterStatus)
        listPage.add(header, BorderLayout.NORTH)
        listPage.add(listContent, BorderLayout.CENTER)
        responsive.onDetailsOnlyChanged = { onDetailsOnlyChanged(it) }

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.accessibleContext.accessibleName = "Skill occurrences"
        list.cellRenderer = OccurrenceRenderer()
        ToolTipManager.sharedInstance().registerComponent(list)
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-skill")
        list.actionMap.put("open-skill", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                if (responsive.isCompact) responsive.showDetailsNow()
                else selectedOccurrence?.skillFile?.let(openSkill)
            }
        })
        listContent.add(listScrollPane, BorderLayout.CENTER)
        empty.border = JBUI.Borders.empty(AgentHubUiComponents.TEXT_LEFT_INSET)
        empty.add(emptyText, BorderLayout.NORTH)
        add(responsive.component, BorderLayout.CENTER)
        clearFilters.addActionListener {
            query = ""
            resetFilters()
            onClearSearch?.invoke()
            renderRows()
        }
        settingsButton.addActionListener { openSettings() }
        bulkMigrationButton.addActionListener {
            if (bulkRunning) {
                cancelBulkMigration()
            } else {
                snapshot?.let {
                    if (failedBulkMigrationCount() > 0) retryBulkFailures(it.skills, it.context)
                    else migrateDuplicates(it.skills, it.context)
                }
            }
        }
        redundantCopyButton.addActionListener { snapshot?.let { cleanUpRedundantCopies(skillGroups(it)) } }
        scope.addActionListener { if (!updatingControls) contextChanged() }
        projectChoice.addActionListener { if (!updatingControls) contextChanged() }
        list.addListSelectionListener {
            if (!it.valueIsAdjusting && !updatingList) {
                renderDetails()
                responsive.selectionChanged()
            }
        }
        com.shutterstar.agenthub.projects.ui.RowContextMenus.install(list, ::createContextMenu)
        list.addMouseListener(object : MouseAdapter() {

            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                val index = list.locationToIndex(event.point)
                if (index < 0 || list.getCellBounds(index, index)?.contains(event.point) != true) return
                if (event.clickCount == 2) selectedOccurrence?.skillFile?.let(openSkill)
                else responsive.showDetailsNow()
            }

        })
        renderRows()
    }

    var onClearSearch: (() -> Unit)? = null

    fun setProjects(projects: List<DiscoveredProject>) {
        val previous = (projectChoice.selectedItem as? DiscoveredProject)?.let(SkillBrowserContext::projectPath)
        val items = projects.filter { SkillBrowserContext.projectPath(it).isNotBlank() }
            .distinctBy(SkillBrowserContext::projectPath).sortedBy { it.name.lowercase() }
        updatingControls = true
        projectChoice.removeAllItems()
        if (items.isNotEmpty()) {
            // "All projects" (a null item) always leads the list, so it's what a fresh combo box
            // selects by default - a specific project is only pre-selected here to preserve a
            // choice the user already made across a refresh, never guessed on first load.
            projectChoice.addItem(null)
            items.forEach(projectChoice::addItem)
            items.firstOrNull { SkillBrowserContext.projectPath(it) == previous }?.let { projectChoice.selectedItem = it }
        }
        // Global remains the default overall scope on first load; a project is only selected once
        // the user explicitly switches to Project scope.
        if (items.isEmpty()) scope.selectedItem = SkillScope.GLOBAL
        updatingControls = false
        contextChanged()
    }

    fun setQuery(value: String) {
        if (query == value) return
        query = value
        renderRows()
    }

    fun refresh() = currentContext()?.let(requestContext)

    /**
     * Refreshes like [refresh], but once the resulting snapshot arrives, selects the first row
     * matching [matches] instead of preserving the previously selected occurrence — used after a
     * mutation (e.g. Promote) turns the selected occurrence into a different, now-relevant row.
     */
    fun refreshPreferring(matches: (SkillOccurrenceRow) -> Boolean) {
        pendingSelector = matches
        refresh()
    }

    /** Selects another occurrence of the same skill (one of its additional sources) in the list, clearing filters that would hide it. */
    private fun goToSource(row: SkillOccurrenceRow, source: SkillSource) {
        resetFilters()
        query = ""
        onClearSearch?.invoke()
        renderRows { candidate ->
            candidate.skill.identity.id == row.skill.identity.id &&
                candidate.context.key == row.context.key &&
                candidate.source.path == source.path
        }
        scrollSelectedIntoView()
        responsive.showDetailsNow()
    }

    /** Opens the central Skills view at an occurrence selected from Projects/Agents Environment. */
    fun navigateTo(context: SkillBrowserContext, skillId: String, sourcePath: String? = null) {
        updatingControls = true
        scope.selectedItem = context.scope
        if (context.scope == SkillScope.PROJECT) {
            val project = context.project
            if (project == null) {
                if (projectChoice.itemCount > 0) projectChoice.selectedIndex = 0
            } else {
                val wanted = SkillBrowserContext.projectPath(project)
                val matching = (0 until projectChoice.itemCount)
                    .mapNotNull(projectChoice::getItemAt)
                    .firstOrNull { SkillBrowserContext.projectPath(it) == wanted }
                if (matching == null) {
                    if (projectChoice.itemCount == 0) projectChoice.addItem(null)
                    projectChoice.addItem(project)
                }
                projectChoice.selectedItem = matching ?: project
            }
        }
        resetFilters()
        updatingControls = false
        updateProjectChoice()
        query = ""
        onClearSearch?.invoke()
        pendingSelector = { row ->
            row.skill.identity.id == skillId &&
                (context.project == null || row.context.project?.let(SkillBrowserContext::projectPath) ==
                    SkillBrowserContext.projectPath(context.project)) &&
                (sourcePath == null || row.source.path == sourcePath ||
                    row.source.shared && row.skill.sources.any { it.path == sourcePath })
        }
        pendingNavigation = true
        lastRequestedContext = context.key
        responsive.showList()
        requestContext(context)
    }

    fun showState(state: SkillBrowserState) {
        if (state.loading && state.snapshot != null && state.snapshot === snapshot) {
            loading = true
            summaryText = "Discovering skills…"
            onSummaryChanged(summaryText)
            return
        }
        snapshot = state.snapshot
        loading = state.loading
        error = state.error
        // A normal (single-project or global) snapshot is always re-derived from its own context +
        // skills here rather than trusting the stored SkillBrowserSnapshot.rows - a data class
        // copy() (as tests reasonably do to tweak one field) keeps the old rows verbatim, and that
        // staleness would silently show the wrong occurrences. Only the "all projects" aggregate
        // snapshot has no single context to re-derive from, so it alone relies on the stored field.
        val snap = state.snapshot
        val rawRows = when {
            snap == null -> emptyList()
            snap.context.scope == SkillScope.PROJECT && snap.context.project == null -> snap.rows
            else -> SkillBrowserModel.rows(snap)
        }
        // A skill shared with N agents by link would otherwise list N(+1) rows, one per distinct
        // filesystem path, even though only one holds real content - see mergeLinkedOccurrences.
        allRows = SkillBrowserModel.mergeLinkedOccurrences(rawRows) { path -> snap?.sourceStats?.get(path)?.isLink == true }
        // An agent filter for an agent this scope no longer has would hide everything for no visible reason.
        if (agentFilter != null && agentFilter !in availableAgentIds()) agentFilter = null
        updateFiltersButton()
        // SkillBrowserController.refresh() delivers a synchronous "loading" state carrying the
        // stale cached snapshot before the real (background-discovered) one arrives. Consuming the
        // one-shot selector on that intermediate call would apply it to rows that don't yet
        // reflect the mutation it was meant for, then discard it before the real data shows up.
        val selector = if (state.loading) null else pendingSelector.also { pendingSelector = null }
        renderRows(selector)
        if (!state.loading && pendingNavigation) {
            pendingNavigation = false
            if (selector != null && selectedOccurrence?.let(selector) == true) {
                scrollSelectedIntoView()
                responsive.showDetailsNow()
            }
        }
    }

    private fun returnToList() {
        if (scope.selectedItem == SkillScope.PROJECT && projectChoice.itemCount > 0 && projectChoice.selectedIndex != 0) {
            updatingControls = true
            projectChoice.selectedIndex = 0
            updatingControls = false
            contextChanged()
        }
        scrollSelectedIntoView()
        list.requestFocusInWindow()
    }
    private fun scrollSelectedIntoView() {
        val index = list.selectedIndex.takeIf { it >= 0 } ?: return
        list.ensureIndexIsVisible(index)
        // The list may have just been reparented by the compact layout; repeat after Swing lays it out.
        SwingUtilities.invokeLater {
            if (list.selectedIndex == index) list.ensureIndexIsVisible(index)
        }
    }

    fun showFileError(message: String) {
        error = message
        renderRows()
    }

    fun setBulkMigrationRunning(running: Boolean) {
        bulkRunning = running
        renderRows()
    }

    /** The snapshot's skills per concrete context: an "all projects" view is one group per project, each with its own project. */
    private fun skillGroups(snapshot: SkillBrowserSnapshot): List<SkillGroup> =
        snapshot.rows.groupBy({ it.context }, { it.skill }).map { (context, skills) -> SkillGroup(skills.distinctBy { it.identity.id }, context) }

    private fun currentContext(): SkillBrowserContext? {
        val selectedScope = scope.selectedItem as SkillScope
        // A null selection here means either "All projects" (a real, selectable item - itemCount
        // > 0) or "nothing to select" (no project has a skill at all - itemCount == 0). Only the
        // latter is not yet a valid context.
        if (selectedScope == SkillScope.PROJECT && projectChoice.itemCount == 0) return null
        val project = projectChoice.selectedItem as? DiscoveredProject
        return SkillBrowserContext(selectedScope, if (selectedScope == SkillScope.PROJECT) project else null)
    }

    private fun updateProjectChoice() {
        projectChoice.isVisible = scope.selectedItem == SkillScope.PROJECT
        projectChoice.toolTipText = selectedProject?.let(SkillBrowserContext::projectPath)
    }
    private fun contextChanged() {
        updateProjectChoice()
        val context = currentContext() ?: return
        if (context.key == lastRequestedContext) return
        lastRequestedContext = context.key
        responsive.showList()
        requestContext(context)
    }

    /** The project filter's current selection; null means "All projects" - its default. Exposed for tests. */
    internal val selectedProject: DiscoveredProject? get() = projectChoice.selectedItem as? DiscoveredProject

    /** Sets all three filters at once (the Filters popup and tests); the header button follows. */
    internal fun setFilters(state: SkillBrowserFilter, ownership: SkillOwnershipFilter, agentId: String?) {
        stateFilter = state
        ownershipFilter = ownership
        agentFilter = agentId
        updateFiltersButton()
        renderRows()
    }

    internal val activeStateFilter: SkillBrowserFilter get() = stateFilter
    internal val activeOwnershipFilter: SkillOwnershipFilter get() = ownershipFilter
    internal val activeAgentFilter: String? get() = agentFilter

    /** Text of the header Filters button, e.g. "Filters (2) ▾" — exposed so the count can be tested. */
    internal val filtersButtonText: String get() = filtersButton.text

    private fun resetFilters() {
        stateFilter = SkillBrowserFilter.ALL
        ownershipFilter = SkillOwnershipFilter.ALL
        agentFilter = null
        updateFiltersButton()
    }

    private fun updateFiltersButton() {
        val active = listOf(
            stateFilter != SkillBrowserFilter.ALL,
            ownershipFilter != SkillOwnershipFilter.ALL,
            agentFilter != null,
        ).count { it }
        filtersButton.text = if (active > 0) "Filters ($active) \u25be" else "Filters \u25be"
    }

    private fun availableAgentIds(): Set<String> = allRows.flatMap { it.agentIds }.toSortedSet()

    /**
     * Radio groups (state / ownership / agent) in a popup under the Filters button. Radios rather
     * than combo boxes: a combo's own dropdown opened from inside a popup is a second popup, and
     * clicking into it can dismiss the first. Choices apply immediately and the popup stays open.
     */
    private fun createFiltersContent(): JPanel {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(AgentHubUiComponents.PANEL_INSET)
        }
        fun <T> addGroup(
            title: String,
            options: List<Triple<T, String, Icon?>>,
            selected: T,
            agentChoices: Boolean = false,
            choose: (T) -> Unit,
        ) {
            content.add(AgentHubUiComponents.filterGroupHeader(title, first = content.componentCount == 0))
            val group = ButtonGroup()
            options.forEach { (value, label, icon) ->
                val radio = JRadioButton(label, value == selected).apply { addActionListener { choose(value) } }
                group.add(radio)
                content.add(if (agentChoices) {
                    AgentHubUiComponents.agentChoiceRow(radio, label, icon)
                } else {
                    AgentHubUiComponents.filterChoiceRow(radio)
                })
            }
        }
        addGroup(
            "State",
            SkillBrowserFilter.entries.map { Triple<SkillBrowserFilter, String, Icon?>(it, it.label, null) },
            stateFilter,
        ) { setFilters(it, ownershipFilter, agentFilter) }
        addGroup(
            "Ownership",
            SkillOwnershipFilter.entries.map { Triple<SkillOwnershipFilter, String, Icon?>(it, it.label, null) },
            ownershipFilter,
        ) { setFilters(stateFilter, it, agentFilter) }
        addGroup(
            "Agent",
            listOf(Triple<String?, String, Icon?>(null, "All agents", null)) +
                availableAgentIds().map { Triple<String?, String, Icon?>(it, displayName(it), agentIcon(it)) },
            agentFilter,
            agentChoices = true,
        ) { setFilters(stateFilter, ownershipFilter, it) }
        return content
    }

    private fun showFiltersPopup() {
        val content = createFiltersContent()
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setRequestFocus(true)
            .setResizable(false)
            .setMovable(false)
            .createPopup()
            .showUnderneathOf(filtersButton)
    }

    private fun renderRows(selector: ((SkillOccurrenceRow) -> Boolean)? = null) {
        val selectedKey = selectedOccurrence?.key
        val rows = SkillBrowserModel.filter(allRows, query, stateFilter, agentFilter, ownershipFilter, isManagedOccurrence)
        updatingList = true
        listModel.clear()
        listModel.addAll(rows)
        val preferredIndex = selector?.let { rows.indexOfFirst(it) }?.takeIf { it >= 0 }
        val selectedIndex = preferredIndex ?: rows.indexOfFirst { it.key == selectedKey }
        if (rows.isNotEmpty()) list.selectedIndex = selectedIndex.takeIf { it >= 0 } ?: 0
        updatingList = false
        val dashboard = SkillBrowserModel.dashboardSummary(snapshot?.skills.orEmpty())
        val sharedRows = allRows.filter { it.source.shared }
        val managedCoverage = sharedRows.sumOf { managedTargetIds(it).size }
        // Agents that read the shared source directly (SkillTargetStatus.NATIVE) never get a
        // managed link/copy - counting them in the denominator would cap "managed coverage" below
        // 100% even when every agent that could possibly need managing already has it.
        val manageableTargetIds = syncTargetIds() - AgentCapabilityRegistry.agentIdsSupportingSharedSkills()
        val eligibleCoverage = sharedRows.size * manageableTargetIds.size
        val observedTargets = snapshot?.targetStatuses.orEmpty().flatMap { (skillId, targets) -> targets.map { skillId to it } }
        val healthyTargets = observedTargets.count { (skillId, target) ->
            target.status == SkillTargetStatus.LINKED || target.status == SkillTargetStatus.NATIVE ||
                (target.status == SkillTargetStatus.COPIED && copiedInSync(skillId, target.agentId, target.fingerprint))
        }
        val brokenTargets = observedTargets.count { (_, target) ->
            target.status == SkillTargetStatus.BROKEN_LINK || target.status == SkillTargetStatus.MISSING_SOURCE
        }
        val outOfSyncTargets = observedTargets.count { (skillId, target) ->
            target.status == SkillTargetStatus.COPIED && !copiedInSync(skillId, target.agentId, target.fingerprint)
        }
        val conflictTargets = observedTargets.count { (_, target) -> target.status == SkillTargetStatus.DIFFERENT }
        val unknownTargets = observedTargets.count { (_, target) ->
            target.status == SkillTargetStatus.NOT_AVAILABLE || target.status == SkillTargetStatus.UNSUPPORTED ||
                target.status == SkillTargetStatus.IDENTICAL_UNMANAGED || target.status == SkillTargetStatus.ERROR
        }
        val migrationCount = snapshot?.let { countMigratable(it.skills, it.context) } ?: 0
        val redundantCount = snapshot?.let { countRedundantCopies(skillGroups(it)) } ?: 0
        // The panel renders once during construction, before AgentHubToolWindowPanel has assigned
        // its mutually-referencing mutation controller. No snapshot means there cannot be a retry.
        val failedMigrationCount = if (snapshot == null) 0 else failedBulkMigrationCount()
        bulkMigrationButton.isVisible = bulkRunning || migrationCount > 0 || failedMigrationCount > 0
        redundantCopyButton.isVisible = redundantCount > 0 || bulkRunning
        redundantCopyButton.isEnabled = !bulkRunning
        redundantCopyButton.text = "Clean up redundant copies…"
        migrationRow.isVisible = bulkMigrationButton.isVisible || redundantCopyButton.isVisible
        bulkMigrationButton.text = when {
            bulkRunning -> "Cancel after current skill"
            failedMigrationCount > 0 -> "Retry migration failures ($failedMigrationCount)…"
            else -> "Migrate duplicates…"
        }
        summaryText = if (loading) {
            "Discovering skills…"
        } else {
            buildList {
                dashboard.label.takeIf(String::isNotEmpty)?.let(::add)
                val health = listOfNotNull(
                    healthyTargets.takeIf { it > 0 }?.let { "$it healthy" },
                    outOfSyncTargets.takeIf { it > 0 }?.let { "$it out of sync" },
                    conflictTargets.takeIf { it > 0 }?.let { "$it conflicts" },
                    brokenTargets.takeIf { it > 0 }?.let { "$it broken" },
                    unknownTargets.takeIf { it > 0 }?.let { "$it unknown" },
                )
                if (health.isNotEmpty()) add("targets " + health.joinToString(" / "))
                if (managedCoverage > 0 && eligibleCoverage > 0) {
                    add("managed coverage $managedCoverage/$eligibleCoverage")
                }
                if (rows.isNotEmpty()) add(rows.size.toString() + " of " + allRows.size + " occurrences")
            }.joinToString(" · ")
        }
        onSummaryChanged(summaryText)
        errorText.text = error.orEmpty()
        errorText.isVisible = error != null
        warningBar.setWarnings(snapshot?.warnings.orEmpty().map { it.message })
        emptyText.text = when {
            loading -> "Discovering skills…"
            error != null -> "Skills could not be loaded. Use Refresh to try again."
            allRows.isNotEmpty() -> "No skills match these filters."
            else -> "No skills discovered in this scope."
        }
        val activeFilters = buildList {
            if (query.isNotBlank()) add("Search: ${query.trim()}")
            if (stateFilter != SkillBrowserFilter.ALL) add("State: ${stateFilter.label}")
            if (ownershipFilter != SkillOwnershipFilter.ALL) add("Ownership: ${ownershipFilter.label}")
            agentFilter?.let { add("Agent: ${displayName(it)}") }
        }
        filterSummary.text = "${rows.size} of ${allRows.size} items" +
            activeFilters.takeIf { it.isNotEmpty() }?.joinToString(" · ", " · ").orEmpty()
        filterStatus.isVisible = activeFilters.isNotEmpty()
        clearFilters.isVisible = activeFilters.isNotEmpty()
        val visibleContent = if (rows.isEmpty()) empty else listScrollPane
        if (listContent.getComponent(0) !== visibleContent) {
            listContent.removeAll()
            listContent.add(visibleContent, BorderLayout.CENTER)
            listContent.revalidate()
            listContent.repaint()
        }
        renderDetails()
        responsive.selectionChanged()
    }

    private fun renderDetails() {
        details.removeAll()
        val row = selectedOccurrence
        if (row == null) {
            shownRowKey = null
            details.revalidate()
            details.repaint()
            return
        }
        detailsHeader.set(DetailsTitle(row.title), listOf("${row.contextLabel} \u00b7 ${row.sourceLabel}", row.stateLabel))
        details.add(detailsHeader, BorderLayout.NORTH)
        val pages = buildList<Pair<String, JComponent>> {
            add("Overview" to overviewPage(row))
            if (row.source.shared) {
                add("Agents" to agentsPage(row))
                if (hasHistoryPage(row)) add("History" to historyPage(row))
            }
        }
        if (pages.size == 1) {
            // A lone "Overview" tab strip would add a row and no choice: show the page directly.
            details.add(scrollPage(pages.single().second), BorderLayout.CENTER)
        } else {
            detailTabs.setTabs(pages.map { (title, content) -> title to scrollPage(content) })
            // A different skill opens on its first tab; a refresh of the same one keeps the tab being read.
            if (row.key != shownRowKey) detailTabs.selectFirst()
            details.add(detailTabs, BorderLayout.CENTER)
        }
        shownRowKey = row.key
        details.revalidate()
        details.repaint()
    }

    private fun scrollPage(content: JComponent): JComponent =
        JBScrollPane(object : JPanel(BorderLayout()), Scrollable {
            override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

            override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
                JBUI.scale(16)

            override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
                visibleRect.height - JBUI.scale(16)

            override fun getScrollableTracksViewportWidth(): Boolean = true

            override fun getScrollableTracksViewportHeight(): Boolean = false
        }.apply { add(content, BorderLayout.NORTH) }).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
        }

    private fun pageColumn(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(
            AgentHubUiComponents.CONTROL_GAP,
            AgentHubUiComponents.TEXT_LEFT_INSET,
            AgentHubUiComponents.PANEL_INSET,
            AgentHubUiComponents.PANEL_INSET,
        )
    }

    private fun addText(column: JPanel, value: String, muted: Boolean = false) {
        column.add(textArea(value).apply {
            if (muted) foreground = JBColor.GRAY
            border = JBUI.Borders.emptyBottom(AgentHubUiComponents.CONTROL_GAP)
            alignmentX = Component.LEFT_ALIGNMENT
        })
    }

    private fun addSingleLineText(column: JPanel, value: String, muted: Boolean = false) {
        column.add(AgentHubUiComponents.singleLineText(value).apply {
            if (muted) foreground = JBColor.GRAY
            border = JBUI.Borders.emptyBottom(AgentHubUiComponents.CONTROL_GAP)
        })
    }

    private fun addFile(column: JPanel, sourcePath: String, relativePath: String) {
        val root = runCatching { Path.of(sourcePath).normalize() }.getOrNull() ?: return
        val file = runCatching { root.resolve(relativePath).normalize() }.getOrNull() ?: return
        if (!file.startsWith(root) || file == root) return
        column.add(com.shutterstar.agenthub.projects.ui.FocusableFileLink(".\\${relativePath.replace('/', '\\')}").apply {
            foreground = JBColor.GRAY
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isFocusable = true
            foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
            accessibleContext.accessibleName = "Open $file"
            com.shutterstar.agenthub.projects.ui.RowContextMenus.install(this,
                selectedBounds = { java.awt.Rectangle(0, 0, width, height) }, selectAt = { true },
                menu = { JPopupMenu().apply {
                    add(JMenuItem("Open").apply { addActionListener { openSkill(file) } })
                    add(JMenuItem("Reveal in Files").apply { addActionListener { revealSkill(file) } })
                    add(JMenuItem("Copy Path").apply { addActionListener { com.shutterstar.agenthub.projects.ui.AgentHubFileActions.copyPath(file.toString()) } })
                } },
            )
            toolTipText = "Double-click to open $file"
            border = JBUI.Borders.emptyBottom(AgentHubUiComponents.CONTROL_GAP)
            alignmentX = Component.LEFT_ALIGNMENT
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-skill-file")
            actionMap.put("open-skill-file", object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) = openSkill(file)
            })
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(event) && event.clickCount == 2) openSkill(file)
                }
            })
        })
    }

    private fun addSection(column: JPanel, title: String, topGap: Int = AgentHubUiComponents.CONTROL_GAP) {
        column.add(JBLabel(title).apply {
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(topGap, 0, AgentHubUiComponents.CONTROL_GAP, 0)
        })
    }

    private fun addDetailDivider(column: JPanel, dotted: Boolean = false) {
        column.add(AgentHubUiComponents.detailDivider(dotted))
    }

    /** Buttons whose label already says everything get no hover. */
    private fun hoverText(action: SkillRowAction): String? =
        action.tooltip.takeUnless { action.label in SELF_EXPLAINING_ACTIONS }

    /** File actions stay compact at every width; tooltips and accessible names retain their labels. */
    private fun buttonRow(actions: List<SkillRowAction>): JPanel =
        CollapsingButtonRow.of(AgentHubUiComponents.SMALL_GAP) {
            actions.forEach { action ->
                val button = JButton(action.label).apply {
                    isEnabled = action.enabled
                    toolTipText = hoverText(action)
                    addActionListener { action.perform() }
                }
                val icon = if (action.group == ActionGroup.FILE) fileActionIcon(action.label) else null
                if (icon != null) {
                    button.text = ""
                    button.icon = icon
                    button.toolTipText = "${action.label}: ${action.tooltip}"
                    button.accessibleContext.accessibleName = action.label
                }
                add(button)
            }
        }

    private fun fileActionIcon(label: String): Icon? = when (label) {
        "Open SKILL.md" -> AllIcons.Actions.EditSource
        "Reveal in Files" -> AllIcons.Nodes.Folder
        else -> null
    }

    /** What the skill is, where it lives, and the two or three things you can do with it. */
    private fun overviewPage(row: SkillOccurrenceRow): JComponent {
        val column = pageColumn()
        row.skill.description?.takeIf(String::isNotBlank)?.let {
            addText(column, it.take(2000))
            addDetailDivider(column)
        }
        addSection(column, "Location", topGap = 0)
        addSingleLineText(column, row.source.path)
        val actions = buttonRow(primaryActions(row).filter { it.group == ActionGroup.FILE || it.group == ActionGroup.MUTATION })
        if (!row.source.shared && row.source.agentId != null && row.source.agentId !in syncTargetIds()) {
            actions.add(JBLabel("Sync adapter not available").apply {
                foreground = JBColor.GRAY
                toolTipText = "This source remains read-only until a sync adapter is available"
            })
        }
        column.add(actions)
        val files = snapshot?.sourceFiles?.get(row.source.path).orEmpty()
        val sourceStat = snapshot?.sourceStats?.get(row.source.path)
        if (files.isNotEmpty() || sourceStat != null) {
            addDetailDivider(column)
            addSection(column, "Files (scripts are never run by AgentHub)", topGap = 0)
            files.forEach { addFile(column, row.source.path, it) }
            sourceStat?.let { stat ->
                addText(column, statSuffix(stat).trimStart('\n'))
                if (stat.fileCount > files.size || files.size >= 200 || stat.truncated) {
                    addText(column, "File list shows up to 200 files within 4 levels; the count includes up to 12 levels.")
                }
            }
        }
        // A linked location (junction/symlink into the shared source) isn't itself content - it's
        // just where an agent finds the real source. Listing all of them here for a skill shared
        // with many agents would just repeat the canonical source's own file count/size N times;
        // that per-agent coverage already belongs to the Agents page. Only a stat computation that
        // couldn't run (null) stays visible rather than risk hiding a genuine source. The source
        // already shown above under "Location" is excluded too - this section is for whatever
        // *else* exists, not a second listing of the skill the user is already looking at.
        // Redundant links/copies get their own section (with a way to remove them), above the sources.
        val redundant = redundantEntries(row)
        if (redundant.isNotEmpty()) {
            addDetailDivider(column)
            addSection(column, redundantTitle(redundant), topGap = 0)
            redundant.forEachIndexed { index, entry ->
                if (index > 0) addDetailDivider(column, dotted = true)
                addRedundantEntry(column, row, entry)
            }
        }
        val redundantPaths = redundant.mapTo(mutableSetOf()) { it.source.path }
        val additionalSources = row.skill.sources.filter { it.scope == row.source.scope }.distinctBy { it.path }
            .filterNot { it.path == row.source.path || it.path in redundantPaths }
            .filterNot { source -> snapshot?.sourceStats?.get(source.path)?.isLink == true }
        if (additionalSources.isNotEmpty()) {
            addDetailDivider(column)
            addSection(column, "Additional sources", topGap = 0)
            additionalSources.forEachIndexed { index, source ->
                if (index > 0) addDetailDivider(column, dotted = true)
                val stat = snapshot?.sourceStats?.get(source.path)
                val label = when {
                    source.system -> "System"
                    source.shared -> "Shared source"
                    else -> "Agent-specific"
                }
                column.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    ownerOf(row, source)?.let { agentId ->
                        add(JBLabel(displayName(agentId), agentIcon(agentId), JLabel.LEADING).apply {
                            accessibleContext.accessibleName = displayName(agentId)
                        })
                    }
                    add(JBLabel(if (source.agentId == null) label else "· $label"))
                })
                addSingleLineText(column, source.path, muted = true)
                statSuffix(stat).trimStart('\n').takeIf(String::isNotEmpty)?.let { addText(column, it, muted = true) }
                val sourcePath = runCatching { Path.of(source.path) }.getOrNull()
                val sourceActions = mutableListOf(
                    SkillRowAction("Open SKILL.md", "Open this source's instructions", ActionGroup.FILE, sourcePath != null) {
                        sourcePath?.resolve("SKILL.md")?.let(openSkill)
                    },
                    SkillRowAction("Reveal in Files", "Reveal this source directory", ActionGroup.FILE, sourcePath != null) {
                        sourcePath?.let(revealSkill)
                    },
                    SkillRowAction("Go to skill", "Select this copy in the skills list", ActionGroup.FILE) { goToSource(row, source) },
                )
                // Any other copy that the opened skill is compared with gets its own way in.
                val sourceSide = sideOf(row, source)
                if (sourceSide != null) {
                    versionSides(row, sourceSide.agentId)?.takeIf { sides -> sides.others.any { it.path == sourceSide.path } }?.let { sides ->
                        val other = sides.others.first { it.path == sourceSide.path }
                        sourceActions.add(versionAction(row, sides))
                    }
                }
                // The counterpart of Resolve Conflict: a copy identical to the shared skill (of an agent that cannot
                // read the shared folder itself) is replaced by a link to it.
                if (row.source.shared) identicalCopyAction(row, source)?.let(sourceActions::add)
                column.add(buttonRow(sourceActions))
            }
        }
        return column
    }

    /** ", 3 files, 21.3 KB" appended to a Sources entry (link sources never reach this - see above). */
    private fun statSuffix(stat: SourceStat?): String {
        if (stat == null) return ""
        val countLabel = "${if (stat.truncated) "${stat.fileCount}+" else stat.fileCount.toString()} file${if (stat.fileCount == 1) "" else "s"}"
        return "\n$countLabel, ${AgentHubUiFormat.formatSize(stat.totalSizeBytes)}"
    }

    /** Groups agents that read the canonical directory directly; other targets retain their own status and actions. */
    private fun agentsPage(row: SkillOccurrenceRow): JComponent {
        val column = pageColumn()
        val observed = snapshot?.targetStatuses?.get(row.skill.identity.id).orEmpty()
        val managedIds = managedTargetIds(row)
        // An agent that reads the shared folder yet keeps its own link/copy is observed as NATIVE, but is not
        // "nothing to manage": it gets its own block with the removal instead of joining the direct readers.
        val redundantIds = redundantEntries(row).mapTo(mutableSetOf()) { it.agentId }
        val agentIds = (observed.map { it.agentId } + managedIds.sorted() + redundantIds.sorted()).distinct()
        val nativeIds = agentIds.filter { agentId ->
            agentId !in redundantIds && observed.any { it.agentId == agentId && it.status == SkillTargetStatus.NATIVE }
        }
        val targetIds = agentIds - nativeIds.toSet()
        if (nativeIds.isNotEmpty()) {
            addSection(column, if (row.source.scope == SkillScope.GLOBAL) "Global shared skills" else "Project shared skills", topGap = 0)
            val sharedDirectory = runCatching { Path.of(row.source.path).parent?.toString() }.getOrNull()
            if (sharedDirectory != null) addSingleLineText(column, sharedDirectory, muted = true)
            column.add(AgentHubUiComponents.verticalBox(opaque = false, leftAligned = true).apply {
                nativeIds.forEach { agentId ->
                    add(JLabel(displayName(agentId), agentIcon(agentId), JLabel.LEADING).apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                        border = JBUI.Borders.emptyBottom(AgentHubUiComponents.SMALL_GAP)
                        accessibleContext.accessibleName = displayName(agentId)
                        toolTipText = "Reads the shared skill directly"
                    })
                }
            })
            addDetailDivider(column)
        }

        // The coverage title and its actions wrap together on a narrow panel.
        val gap = JBUI.scale(AgentHubUiComponents.SMALL_GAP)
        val header = CollapsingButtonRow.of(0) {
            border = JBUI.Borders.emptyBottom(AgentHubUiComponents.CONTROL_GAP)
            add(JBLabel("Agent coverage").apply { font = font.deriveFont(Font.BOLD) })
            primaryActions(row).filter { it.group == ActionGroup.RETRY }.forEach { action ->
                add(Box.createHorizontalStrut(gap))
                add(JButton(action.label).apply {
                    toolTipText = action.tooltip
                    addActionListener { action.perform() }
                })
            }
            if (managedIds.isNotEmpty()) {
                add(Box.createHorizontalStrut(gap))
                add(JButton("Repair All").apply {
                    toolTipText = "Repair every managed agent for this skill in one pass"
                    addActionListener { repairAllSkill(row) }
                })
            }
        }
        column.add(header)
        when {
            agentIds.isEmpty() -> addText(column, "No agents observed for this skill yet. Use Share with agents\u2026 to add some.")
            targetIds.isEmpty() -> addText(column, "No links or copies to manage. The agents above read the shared skills directly.")
            else -> targetIds.forEachIndexed { index, agentId ->
                if (index > 0) addDetailDivider(column, dotted = true)
                column.add(agentBlock(row, agentId, observed.firstOrNull { it.agentId == agentId }, agentId in managedIds))
            }
        }
        return column
    }

    private fun agentBlock(row: SkillOccurrenceRow, agentId: String, target: ObservedSkillTarget?, managed: Boolean): JComponent {
        val block = AgentHubUiComponents.verticalBox(leftAligned = true)
        fun secondary(text: String) {
            block.add(JBLabel(text).apply {
                putClientProperty("html.disable", true)
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
                toolTipText = text
            })
        }
        val redundant = redundantEntries(row).firstOrNull { it.agentId == agentId }
        val identicalCopy = target != null && (
            target.status == SkillTargetStatus.IDENTICAL_UNMANAGED ||
                (target.status == SkillTargetStatus.COPIED && copiedInSync(row.skill.identity.id, agentId, target.fingerprint))
            )
        val removeCopy = replaceCopyAction(row, agentId, target, identicalCopy)
        block.add(JPanel(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
            add(JLabel(agentIcon(agentId)).apply {
                text = displayName(agentId)
                font = font.deriveFont(Font.BOLD)
            }, BorderLayout.WEST)
            val state = redundant?.let { "Redundant ${it.noun}" } ?: target?.let { targetStateLabel(row, it) }
            state?.let { add(JBLabel(it).apply { foreground = JBColor.GRAY }, BorderLayout.EAST) }
        })
        if (redundant != null) {
            secondary("Reads the shared folder directly")
            secondary(redundant.source.path)
        } else if (target != null) {
            val ownership = when (target.owner) {
                SyncOwner.AGENTHUB -> "AgentHub-managed"
                SyncOwner.EXTERNAL -> "Externally managed"
                SyncOwner.MANUAL -> "Manual / unmanaged"
                SyncOwner.NATIVE -> "Reads shared source directly – nothing to manage"
                SyncOwner.UNKNOWN -> "Unknown ownership"
            }
            val fallback = if (target.requestedMode == SkillSyncMode.SYMLINK && target.availableLinkMode == null) {
                "Link unavailable; reviewed Copy fallback"
            } else {
                null
            }
            secondary(
                listOfNotNull(
                    target.managedMode?.name?.lowercase()?.replaceFirstChar(Char::uppercase),
                    ownership,
                    fallback,
                ).joinToString(" \u00b7 "),
            )
            target.renameCandidatePath?.let { secondary("Rename candidate: $it (review only)") }
            target.targetPath?.toString()?.let(::secondary)
        } else {
            secondary("AgentHub-managed")
        }
        if (redundant != null) {
            block.add(buttonRow(listOf(removeRedundantAction(row, redundant))).apply {
                border = JBUI.Borders.emptyTop(AgentHubUiComponents.SMALL_GAP)
            })
        } else if (managed) {
            block.add(buttonRow(withRemoveCopy(managedActions(row, agentId), removeCopy)).apply {
                border = JBUI.Borders.emptyTop(AgentHubUiComponents.SMALL_GAP)
            })
        } else if (canStopExisting(target)) {
            // Not created by AgentHub, but the setting lets it take the sharing away (after a backup).
            block.add(buttonRow(withRemoveCopy(managedActions(row, agentId).filter { it.label == "Stop Sharing" }, removeCopy)).apply {
                border = JBUI.Borders.emptyTop(AgentHubUiComponents.SMALL_GAP)
            })
        } else if (removeCopy != null) {
            block.add(buttonRow(listOf(removeCopy)).apply {
                border = JBUI.Borders.emptyTop(AgentHubUiComponents.SMALL_GAP)
            })
        } else if (target?.status in setOf(SkillTargetStatus.NOT_AVAILABLE, SkillTargetStatus.DIFFERENT, SkillTargetStatus.BROKEN_LINK) &&
            agentId in syncTargetIds()
        ) {
            block.add(buttonRow(listOf(SkillRowAction(
                "Start Sharing",
                "Share this skill with ${displayName(agentId)}",
                ActionGroup.MUTATION,
            ) { startSharingSkill(row, agentId) })).apply {
                border = JBUI.Borders.emptyTop(AgentHubUiComponents.SMALL_GAP)
            })
        }
        return block
    }

    /** [removeCopy] goes right before Stop Sharing (or last when there is none). */
    private fun withRemoveCopy(actions: List<SkillRowAction>, removeCopy: SkillRowAction?): List<SkillRowAction> {
        if (removeCopy == null) return actions
        val stop = actions.indexOfFirst { it.label == "Stop Sharing" }
        return if (stop < 0) actions + removeCopy else actions.take(stop) + removeCopy + actions.drop(stop)
    }

    private fun canStopExisting(target: ObservedSkillTarget?): Boolean =
        target != null && manageExistingTargets() && !target.ownershipVerified &&
            target.status in setOf(SkillTargetStatus.LINKED, SkillTargetStatus.IDENTICAL_UNMANAGED)

    private fun targetStateLabel(row: SkillOccurrenceRow, target: ObservedSkillTarget): String {
        val canonicalFingerprint = row.skill.sources.firstOrNull { it.shared }?.fingerprint
        val targetFingerprint = target.fingerprint ?: row.skill.sources.firstOrNull { it.agentId == target.agentId }?.fingerprint
        return if (target.status == SkillTargetStatus.COPIED && canonicalFingerprint != null && canonicalFingerprint != targetFingerprint) {
            "Copied \u00b7 Out of sync"
        } else {
            target.status.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
        }
    }

    private fun canRestoreBackup(row: SkillOccurrenceRow): Boolean =
        row.skill.identity.id in snapshot?.backupSkillIds.orEmpty() || hasBackups(row)

    /** The History page exists only when there is history to read or a backup to restore. */
    private fun hasHistoryPage(row: SkillOccurrenceRow): Boolean = hasHistory(row) || canRestoreBackup(row)

    private fun historyPage(row: SkillOccurrenceRow): JComponent {
        val column = pageColumn()
        val items = historyEntries(row)
        // Restore Backup sits above the (up to 50) entries so it never needs a scroll to find.
        val actions = primaryActions(row).filter { it.group == ActionGroup.HISTORY }
        if (actions.isNotEmpty()) {
            column.add(buttonRow(actions))
            if (items.isNotEmpty()) addDetailDivider(column)
        }
        if (items.isEmpty()) addText(column, "No recorded changes.", muted = true)
        items.forEachIndexed { index, item ->
            if (index > 0) addDetailDivider(column)
            column.add(historyEntry(item))
        }
        return column
    }

    /** One recorded operation: when / what / result, the agents it touched, and Undo while it is still reversible. */
    private fun historyEntry(item: SkillHistoryItem): JComponent {
        val entry = item.entry
        return AgentHubUiComponents.detailBlock().apply {
            add(JBLabel("${AgentHubUiFormat.dateTime.format(entry.timestamp)} · ${entry.action.label()} · ${entry.result.label()}").apply {
                font = font.deriveFont(Font.BOLD)
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyBottom(AgentHubUiComponents.SMALL_GAP)
            })
            if (entry.affectedAgents.isNotEmpty()) {
                add(JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(AgentHubUiComponents.CONTROL_GAP), 0, JBUI.scale(520))).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    isOpaque = false
                    entry.affectedAgents.sorted().forEach { agentId ->
                        add(JLabel(displayName(agentId), agentIcon(agentId), JLabel.LEADING))
                    }
                })
            }
            if (item.undoable) {
                add(JButton("Undo").apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    addActionListener { undoOperation(entry.operationId) }
                })
            } else if (item.backupRemoved) {
                add(JBLabel("Undo unavailable: a backup this change needs was removed.").apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    foreground = JBColor.GRAY
                })
            }
        }
    }

    private fun primaryActions(row: SkillOccurrenceRow): List<SkillRowAction> = buildList {
        add(SkillRowAction("Open SKILL.md", "Open the skill instructions", ActionGroup.FILE, row.skillFile != null) { row.skillFile?.let(openSkill) })
        add(SkillRowAction("Reveal in Files", "Reveal the skill directory", ActionGroup.FILE) {
            runCatching { Path.of(row.source.path) }.getOrNull()?.let(revealSkill)
        })
        if (row.source.shared) {
            add(SkillRowAction("Share with agents\u2026", "Pick the installed agents to share this skill with", ActionGroup.MUTATION) { shareSkill(row) })
            if (canRestoreBackup(row)) {
                add(SkillRowAction("Restore Backup\u2026", "Restore a backup after reviewing its impact", ActionGroup.HISTORY) { restoreBackup(row) })
            }
            val failed = retryFailedTargetIds(row)
            if (failed.isNotEmpty()) add(SkillRowAction("Retry Failed Targets (${failed.size})", "Re-plan only failed targets", ActionGroup.RETRY) {
                retryFailedTargets(row)
            })
        } else {
            val agentId = ownerOf(row, row.source)
            // A shared skill has nothing to move; differing copies are resolved from the shared skill.
            if (sharedSourceOf(row) != null) {
                identicalCopyAction(row, row.source)?.let(::add)
            } else if (agentId != null && agentId in syncTargetIds()) {
                add(SkillRowAction("Move to Shared & Share…", "Move this skill into the shared folder and choose which agents get it", ActionGroup.MUTATION) { promoteSkill(row) })
            }
        }
    }

    /** Vendor-provided copies can be promoted too (the dialog warns); only overwriting one stays blocked. */
    private fun versionAction(row: SkillOccurrenceRow, sides: VersionSides) = SkillRowAction(
        "Resolve Conflict…",
        "Compare this skill with another version and decide which one to keep",
        ActionGroup.MUTATION,
    ) { resolveVersions(row, sides) }

    private fun sideOf(row: SkillOccurrenceRow, source: SkillSource): VersionSide? {
        val path = runCatching { Path.of(source.path).toAbsolutePath().normalize() }.getOrNull() ?: return null
        if (source.shared) return VersionSide(null, path)
        val owner = ownerOf(row, source)?.takeIf { it in syncTargetIds() } ?: return null
        return VersionSide(owner, path, system = source.system)
    }

    /**
     * What the comparison dialog shows: always the opened skill on the left, against the shared source
     * when it is an agent copy that has one, against its differing agent copies when it is the shared
     * source, otherwise against the other copies. Null when there is nothing to compare with.
     */
    private fun versionSides(row: SkillOccurrenceRow, preferredAgentId: String? = null): VersionSides? {
        val current = sideOf(row, row.source) ?: return null
        val sources = row.skill.sources.filter { it.scope == row.source.scope }
        val canonical = sources.firstOrNull { it.shared }
        val candidates = when {
            row.source.shared -> sources.filter { !it.shared }
            // The shared version first, then every other copy: the opened skill is always what they are compared with.
            canonical != null -> listOf(canonical) + sources.filter { !it.shared }
            else -> sources.filter { !it.shared }
        }
        // Only real differences are worth comparing: identical copies, and folders that are just a link into
        // another skill (their content is that skill, already listed), never become a candidate.
        val linkPaths = snapshot?.sourceStats.orEmpty().filterValues { it.isLink }.keys
        val others = candidates
            .filter { it.path !in linkPaths && it.fingerprint != row.source.fingerprint }
            .mapNotNull { sideOf(row, it) }
            .filter { it.path != current.path }
            .distinctBy { it.path }
        return others.takeIf { it.isNotEmpty() }?.let { VersionSides(current, it, preferredAgentId, sharedExists = canonical != null) }
    }
    /** An agent's own link or identical copy of a shared skill, although the agent reads the shared folder on its own. */
    private class RedundantEntry(val agentId: String, val source: SkillSource, val link: Boolean) {
        val noun get() = if (link) "link" else "copy"
    }

    /** The shared source of the opened skill (in the opened scope), if it has one. */
    private fun sharedSourceOf(row: SkillOccurrenceRow): SkillSource? =
        row.skill.sources.firstOrNull { it.shared && it.scope == row.source.scope }

    /** The agent whose own skill directory [source] is; the same strict rule as the clean-up. */
    private fun strictOwnerOf(row: SkillOccurrenceRow, source: SkillSource): String? {
        val listedUnder = row.skill.sources.filter { it.path == source.path }.mapNotNull { it.agentId }.distinct()
        return owningAgentStrict(source.path, listedUnder, source.scope, row.context.project)
    }

    /** True for a symlink or junction (the stats say so); such an entry is never a real copy. */
    private fun isLinkSource(source: SkillSource): Boolean = snapshot?.sourceStats?.get(source.path)?.isLink == true

    /**
     * [source] as a redundant entry of the opened skill's shared source, when it is one: an agent that reads the
     * shared folder itself and keeps a link to it or an identical copy. A copy that differs is a conflict, handled
     * by Resolve Conflict, never redundant.
     */
    private fun redundantEntry(row: SkillOccurrenceRow, source: SkillSource): RedundantEntry? {
        val shared = sharedSourceOf(row) ?: return null
        if (source.shared || source.system || source.scope != shared.scope) return null
        val link = isLinkSource(source)
        if (!link && (source.fingerprint == null || source.fingerprint != shared.fingerprint)) return null
        val agentId = strictOwnerOf(row, source)?.takeIf { it in AgentCapabilityRegistry.agentIdsSupportingSharedSkills() } ?: return null
        return RedundantEntry(agentId, source, link)
    }

    /** Empty unless [row] is the shared source. */
    private fun redundantEntries(row: SkillOccurrenceRow): List<RedundantEntry> =
        if (!row.source.shared) emptyList()
        else row.skill.sources.distinctBy { it.path }.mapNotNull { redundantEntry(row, it) }.sortedBy { it.agentId }

    /**
     * "Remove copy" for an agent that cannot read the shared folder: its identical real copy of the opened skill's
     * shared source is replaced by a link to it (the ordinary share operation, so the plan review and Undo apply).
     * Null when there is no shared source, the agent has no sync adapter, the copy is not identical, or links are not possible.
     */
    private fun replaceCopyAction(row: SkillOccurrenceRow, agentId: String, target: ObservedSkillTarget?, identical: Boolean): SkillRowAction? {
        if (sharedSourceOf(row) == null || !identical || agentId !in syncTargetIds() || !canReplaceCopyWithLink(agentId)) return null
        if (target != null && (target.requestedMode != SkillSyncMode.SYMLINK || target.availableLinkMode == null)) return null
        return SkillRowAction(
            "Remove copy",
            "Replace ${displayName(agentId)}'s copy with a link to the shared skill",
            ActionGroup.MUTATION,
        ) { startSharingSkill(row, agentId) }
    }

    /** Remove copy for an identical copy of the shared source: dropped when the agent reads the shared folder, else replaced by a link. */
    private fun identicalCopyAction(row: SkillOccurrenceRow, source: SkillSource): SkillRowAction? {
        val shared = sharedSourceOf(row) ?: return null
        if (source.shared || source.system || isLinkSource(source)) return null
        redundantEntry(row, source)?.let { return removeRedundantAction(row, it) }
        if (source.fingerprint == null || source.fingerprint != shared.fingerprint) return null
        val agentId = strictOwnerOf(row, source) ?: return null
        val target = snapshot?.targetStatuses?.get(row.skill.identity.id)?.firstOrNull { it.agentId == agentId }
        return replaceCopyAction(row, agentId, target, true)
    }

    private fun removeRedundantAction(row: SkillOccurrenceRow, entry: RedundantEntry) = SkillRowAction(
        "Remove ${entry.noun}",
        "Remove ${displayName(entry.agentId)}'s ${entry.noun}" + if (entry.link) "" else " (backed up first)",
        ActionGroup.MUTATION,
    ) { removeRedundantCopy(row, entry.agentId) }

    /** "Redundant links", "Redundant copies" or both, for the section that lists [entries]. */
    private fun redundantTitle(entries: List<RedundantEntry>): String = when {
        entries.all { it.link } -> "Redundant links"
        entries.none { it.link } -> "Redundant copies"
        else -> "Redundant links and copies"
    }

    /** One redundant entry: who, what kind, where it is, and the way to remove it. */
    private fun addRedundantEntry(column: JPanel, row: SkillOccurrenceRow, entry: RedundantEntry) {
        column.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(displayName(entry.agentId), agentIcon(entry.agentId), JLabel.LEADING).apply {
                accessibleContext.accessibleName = displayName(entry.agentId)
            })
            add(JBLabel("· Redundant ${entry.noun}"))
        })
        addSingleLineText(column, entry.source.path, muted = true)
        column.add(buttonRow(listOf(removeRedundantAction(row, entry))))
    }

    /** The agent a source directory belongs to; discovery may list one folder under every agent that scans it. */
    private fun ownerOf(row: SkillOccurrenceRow, source: SkillSource): String? {
        val candidates = row.skill.sources.filter { it.path == source.path && it.agentId != null }.mapNotNull { it.agentId }.distinct()
        return if (candidates.size <= 1) candidates.firstOrNull() ?: source.agentId
        else owningAgent(source.path, candidates, row.source.scope, row.context.project) ?: source.agentId
    }

    private fun copiedInSync(skillId: String, targetAgentId: String, fingerprint: String?): Boolean {
        val skill = snapshot?.skills.orEmpty().firstOrNull { it.identity.id == skillId } ?: return false
        val canonicalFingerprint = skill.sources.firstOrNull { it.shared }?.fingerprint
        val targetFingerprint = fingerprint ?: skill.sources.firstOrNull { it.agentId == targetAgentId }?.fingerprint
        return canonicalFingerprint != null && canonicalFingerprint == targetFingerprint
    }

    private fun managedActions(row: SkillOccurrenceRow, agentId: String): List<SkillRowAction> {
        val target = snapshot?.targetStatuses?.get(row.skill.identity.id)?.firstOrNull { it.agentId == agentId }
        val inSync = target?.status == SkillTargetStatus.LINKED ||
            (target?.status == SkillTargetStatus.COPIED && copiedInSync(row.skill.identity.id, agentId, target.fingerprint))
        val repair = target?.status == SkillTargetStatus.BROKEN_LINK || target?.status == SkillTargetStatus.MISSING_SOURCE
        return listOf(
            SkillRowAction(if (inSync) "In sync" else if (repair) "Repair" else "Resync",
                if (inSync) "This target already matches the shared source" else if (repair) "Recreate this broken target after reviewing the plan" else "Refresh this target after reviewing the plan",
                ActionGroup.MUTATION, !inSync) { resyncSkill(row, agentId) },
            SkillRowAction("Stop Sharing", "Remove this target (after a backup)", ActionGroup.MUTATION) { stopSharingSkill(row, agentId) },
        )
    }

    private fun createContextMenu(row: SkillOccurrenceRow): JPopupMenu = JPopupMenu().apply {
        var previousGroup: ActionGroup? = null
        primaryActions(row).forEach { action ->
            if (previousGroup != null && previousGroup != action.group) addSeparator()
            previousGroup = action.group
            add(JMenuItem(action.label).apply {
                isEnabled = action.enabled
                toolTipText = action.tooltip
                addActionListener { action.perform() }
            })
        }
        addSeparator()
        add(JMenuItem("Copy source path").apply {
            addActionListener { com.shutterstar.agenthub.projects.ui.AgentHubFileActions.copyPath(row.source.path) }
        })
        if (row.source.shared) {
            val managed = managedTargetIds(row).sorted()
            if (managed.isNotEmpty()) {
                addSeparator()
                add(JMenu("Managed agents").apply {
                    managed.forEach { agentId ->
                        add(JMenu(displayName(agentId)).apply {
                            icon = agentIcon(agentId)
                            managedActions(row, agentId).forEach { action ->
                                add(JMenuItem(action.label).apply {
                                    isEnabled = action.enabled
                                    toolTipText = action.tooltip
                                    addActionListener { action.perform() }
                                })
                            }
                        })
                    }
                    addSeparator()
                    add(JMenuItem("Repair All").apply { addActionListener { repairAllSkill(row) } })
                })
            }
        }
    }

    private inner class OccurrenceRenderer : ListCellRenderer<SkillOccurrenceRow> {
        override fun getListCellRendererComponent(list: JList<out SkillOccurrenceRow>, value: SkillOccurrenceRow, index: Int, selected: Boolean, focus: Boolean): Component {
            val colors = AgentHubUiComponents.rowTextColors(list.foreground, selected)
            val content = AgentHubUiComponents.verticalBox(opaque = false, border = AgentHubUiComponents.listRowBorder())
            // A vendor-shipped/synced (system) occurrence is muted - same layout, lower contrast
            // and no bold weight - so it reads as second-tier without being hidden outright.
            val muted = value.source.system
            content.add(JBLabel(value.title).apply {
                putClientProperty("html.disable", true)
                font = if (muted) font else font.deriveFont(Font.BOLD)
                foreground = if (muted) colors.secondaryForeground else colors.foreground
            })
            content.add(JBLabel("${value.contextLabel} · ${value.sourceLabel}").apply {
                putClientProperty("html.disable", true)
                foreground = colors.secondaryForeground
            })
            val status = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(AgentHubUiComponents.SMALL_GAP), 0)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
            // The shared/canonical source's own agentId is null, so it would otherwise be the only
            // row with no icon at all here - a dedicated glyph marks it instead of leaving it blank.
            if (value.source.shared) {
                status.add(JLabel(AgentHubUiComponents.sharedSkillIcon).apply {
                    toolTipText = "Shared source"
                    accessibleContext.accessibleName = "Shared source"
                })
            }
            value.agentIds.forEach { id -> status.add(JLabel(agentIcon(id)).apply {
                toolTipText = displayName(id)
                accessibleContext.accessibleName = displayName(id)
            }) }
            status.add(JBLabel(value.stateLabel).apply {
                foreground = colors.secondaryForeground
            })
            content.add(status)
            return RoundedSelectionPanel.wrap(content).apply {
                background = list.background
                selectionColor = AgentHubUiComponents.rowHighlight(selected, hover.isHovered(index))
                selectionArc = JBUI.scale(AgentHubUiComponents.SELECTION_ARC)
                selectionInsets = AgentHubUiComponents.listSelectionInsets()
            }
        }
    }

    /** Where a row action is offered in the details: the Overview buttons, the History page, or the Agents page. */
    private enum class ActionGroup { FILE, MUTATION, HISTORY, RETRY }

    private data class SkillRowAction(
        val label: String,
        val tooltip: String,
        val group: ActionGroup,
        val enabled: Boolean = true,
        val perform: () -> Unit,
    )

    companion object {
        private val SELF_EXPLAINING_ACTIONS = setOf("Go to skill", "Share with agents…", "Start Sharing", "Stop Sharing")

        private fun textArea(value: String) = com.shutterstar.agenthub.projects.ui.WidthAwareTextArea().apply {
            // Static detail text must not scroll its page to the end when it is populated.
            (caret as DefaultCaret).updatePolicy = DefaultCaret.NEVER_UPDATE
            text = value
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            font = UIManager.getFont("Label.font")
            foreground = JBColor.foreground()
        }
    }
}

/**
 * One audit entry as the History page shows it; [undoable] is true while its changes can still be reversed,
 * [backupRemoved] when it could be but a backup it needs is gone.
 */
internal data class SkillHistoryItem(
    val entry: com.shutterstar.agenthub.environment.skills.sync.audit.SyncAuditEntry,
    val undoable: Boolean,
    val backupRemoved: Boolean = false,
)
