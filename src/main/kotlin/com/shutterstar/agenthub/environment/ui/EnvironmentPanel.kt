package com.shutterstar.agenthub.environment.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.UserFacingError
import com.shutterstar.agenthub.environment.discovery.AgentEnvironmentDiscoveryService
import com.shutterstar.agenthub.environment.discovery.ProjectEnvironmentDiscoveryService
import com.shutterstar.agenthub.environment.model.AgentEnvironment
import com.shutterstar.agenthub.environment.model.ProjectEnvironment
import com.shutterstar.agenthub.environment.model.visibleTo
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexService
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.ui.AgentHubFileActions
import com.shutterstar.agenthub.projects.ui.AgentHubUiComponents
import com.shutterstar.agenthub.projects.ui.CollapsibleWarningBar
import com.shutterstar.agenthub.projects.ui.DetailsScope
import com.shutterstar.agenthub.projects.ui.RoundedSelectionPanel
import com.shutterstar.agenthub.projects.ui.DetailsStrip
import com.shutterstar.agenthub.projects.ui.TableHoverTracker
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicLong
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButton
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer

/**
 * The Environment tab shared by the Projects and Agents details ([DetailsScope]). One table in
 * both directions: Item · Type · **Agent** (icons of the agents that have the item) for a project,
 * Item · Type · **Location** (project name or "Global") for an agent — agent context never names
 * the agent, and mixes global and project occurrences, hence its extra Scope filter in the popup.
 *
 * Activating a row (double-click / Enter) deep-links a skill to the Skills tab and opens an MCP
 * config or instruction file in the editor; the context menu adds Reveal in Files / Copy Path for
 * every type. The selected row's path, scope and consistency show in a strip under the table, and
 * discovery warnings sit in their own collapsible bar above it instead of being table rows.
 * Rendering only ever shows normalized metadata — never MCP commands, URLs or values (opening a
 * config file is the user's own action, in the IDE editor).
 */
internal class EnvironmentPanel(
    private val currentProject: Project,
    private val discoveryService: ProjectEnvironmentDiscoveryService = ProjectEnvironmentDiscoveryService(),
    private val agentDiscoveryService: AgentEnvironmentDiscoveryService = AgentEnvironmentDiscoveryService(discoveryService),
    private val openSkill: (SkillScope, String, String?, DiscoveredProject?) -> Unit = { _, _, _, _ -> },
    private val execute: (() -> Unit) -> Unit = { AppExecutorUtil.getAppExecutorService().submit(it) },
    private val deliverResult: (() -> Unit) -> Unit = { ApplicationManager.getApplication().invokeLater(it) },
) : JPanel(BorderLayout()) {
    private val summaryLabel = AgentHubUiComponents.wrappingStatusText()
    private val typeFilter = fixedWidthCombo(TYPE_FILTER_OPTIONS)
    private val filtersButton = JButton("Filters \u25be")
    private var statusFilter = SkillFilter.ALL
    private var scopeFilter = ScopeFilter.ALL
    private var agentFilter: String? = null
    private val comparisonModel = EnvironmentComparisonTableModel()
    private val comparisonTable = object : JBTable(comparisonModel) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            val column = columnAtPoint(event.point)
            if (row < 0 || column < 0) return null
            val cellRect = getCellRect(row, column, false)
            val renderer = prepareRenderer(getCellRenderer(row, column), row, column)
            renderer.setBounds(cellRect)
            renderer.invalidate()
            renderer.validate()
            val relative = Point(event.x - cellRect.x, event.y - cellRect.y)
            val deepest = SwingUtilities.getDeepestComponentAt(renderer, relative.x, relative.y)
            return (deepest as? JComponent)?.toolTipText ?: comparisonModel.rowAt(row)?.let { item ->
                when (column) {
                    // Same wording as every other "this opens a file" hover in the tool window.
                    0 -> if (item.category == "Skill") {
                        "Double-click to show in the Skills tab".takeIf { item.skillId != null }
                    } else {
                        item.sourcePath?.let { "Double-click to open $it" }
                    }
                    THIRD_COLUMN_INDEX -> if (scope is DetailsScope.ForAgent) {
                        null
                    } else {
                        lastComparison?.agents?.filter { it.id in item.agentIds }?.joinToString(", ") { it.name }
                    }
                    else -> null
                }
            }
        }
    }
    private val tableHover = TableHoverTracker(comparisonTable)
    private val agentColumnRenderer = AgentIconListCellRenderer(tableHover)
    private val locationColumnRenderer = ComparisonTextCellRenderer(
        tableHover,
        RoundedSelectionPanel.Corners.RIGHT,
        0,
        AgentHubUiComponents.SELECTION_HORIZONTAL_INSET,
    )
    private val warningBar = CollapsibleWarningBar()
    private val detailScroll = DetailsStrip("Selected environment item details")
    private val filterSummary = AgentHubUiComponents.wrappingStatusText()
    private val clearFiltersButton = JButton("Clear filters").apply {
        addActionListener { resetFilters() }
    }
    private val filterStatus = AgentHubUiComponents.filterStatusRow(filterSummary, clearFiltersButton)
    private val requestSequence = AtomicLong()
    private var lastComparison: EnvironmentComparison? = null
    private var scope: DetailsScope? = null
    private var scopeGeneration = -1L
    private var retainedSelection: List<Any?>? = null
    private var disposed = false

    init {
        border = JBUI.Borders.empty(AgentHubUiComponents.PANEL_INSET, 0, 0, 0)
        summaryLabel.border = JBUI.Borders.empty(
            0,
            AgentHubUiComponents.TEXT_LEFT_INSET,
            AgentHubUiComponents.CONTROL_GAP,
            0,
        )
        summaryLabel.font = summaryLabel.font.deriveFont(Font.BOLD)
        summaryLabel.isVisible = false
        comparisonTable.fillsViewportHeight = true
        comparisonTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        comparisonTable.showVerticalLines = false
        comparisonTable.showHorizontalLines = false
        comparisonTable.intercellSpacing = Dimension(0, JBUI.scale(AgentHubUiComponents.SELECTION_VERTICAL_INSET * 2))
        comparisonTable.tableHeader = null
        comparisonTable.columnModel.getColumn(0).cellRenderer = ComparisonTextCellRenderer(
            tableHover,
            RoundedSelectionPanel.Corners.LEFT,
            AgentHubUiComponents.SELECTION_HORIZONTAL_INSET,
            0,
        )
        comparisonTable.columnModel.getColumn(1).cellRenderer =
            ComparisonTextCellRenderer(tableHover, RoundedSelectionPanel.Corners.NONE, 0, 0)
        comparisonTable.columnModel.getColumn(THIRD_COLUMN_INDEX).cellRenderer = agentColumnRenderer
        ToolTipManager.sharedInstance().registerComponent(comparisonTable)
        com.shutterstar.agenthub.projects.ui.RowContextMenus.install(comparisonTable) { index -> comparisonModel.rowAt(index)?.let(::contextMenu) }
        comparisonTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event) || event.clickCount != 2 || event.isPopupTrigger) return
                comparisonModel.rowAt(comparisonTable.rowAtPoint(event.point))?.let(::activate)
            }


        })
        // Enter opens the selected row (JTable's own Enter would just move the selection down).
        comparisonTable.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTIVATE_ACTION)
        comparisonTable.actionMap.put(
            ACTIVATE_ACTION,
            object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) {
                    selectedRow()?.let(::activate)
                }
            },
        )
        comparisonTable.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) updateDetailStrip()
        }
        val header = JPanel(BorderLayout())
        header.add(summaryLabel, BorderLayout.NORTH)
        header.add(filterBar(), BorderLayout.SOUTH)
        add(header, BorderLayout.NORTH)
        val center = JPanel(BorderLayout())
        center.add(JPanel(BorderLayout()).apply {
            add(warningBar, BorderLayout.NORTH)
            add(filterStatus, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        center.add(AgentHubUiComponents.alignedBorderlessScrollPane(comparisonTable), BorderLayout.CENTER)
        add(center, BorderLayout.CENTER)
        add(detailScroll, BorderLayout.SOUTH)
        updateFiltersButton()
    }

    private fun filterBar(): JPanel {
        val bar = JPanel(BorderLayout(JBUI.scale(AgentHubUiComponents.CONTROL_GAP), 0))
        bar.border = JBUI.Borders.empty(
            0,
            AgentHubUiComponents.TEXT_LEFT_INSET,
            AgentHubUiComponents.CONTROL_GAP,
            0,
        )
        bar.add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(AgentHubUiComponents.SMALL_GAP), 0)).apply {
            isOpaque = false
            typeFilter.accessibleContext.accessibleName = "Environment type"
            add(typeFilter)
        }, BorderLayout.WEST)
        filtersButton.apply {
            accessibleContext.accessibleName = "Environment filters"
            addActionListener { showFiltersPopup() }
        }
        bar.add(filtersButton, BorderLayout.EAST)
        typeFilter.addActionListener { renderTable() }
        return bar
    }

    private fun createFiltersContent(): JPanel {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(AgentHubUiComponents.PANEL_INSET)
        }
        fun <T> addGroup(
            title: String,
            options: List<Pair<T, String>>,
            selected: T,
            agentChoices: Boolean = false,
            choose: (T) -> Unit,
        ) {
            content.add(AgentHubUiComponents.filterGroupHeader(title, first = content.componentCount == 0))
            val group = ButtonGroup()
            options.forEach { (value, label) ->
                val radio = JRadioButton(label, value == selected).apply {
                    group.add(this)
                    addActionListener { choose(value) }
                }
                if (agentChoices) {
                    val agentId = value as? String
                    content.add(AgentHubUiComponents.agentChoiceRow(
                        radio,
                        label,
                        agentId?.let(AgentHubUiComponents::faviconFor),
                    ))
                } else {
                    content.add(AgentHubUiComponents.filterChoiceRow(radio))
                }
            }
        }
        addGroup("Status", listOf(SkillFilter.ALL to "All", SkillFilter.SHARED to "Shared", SkillFilter.CONFLICTS to "Conflicts"), statusFilter) {
            statusFilter = it
            updateFiltersButton()
            renderTable()
        }
        addGroup("Scope", listOf(ScopeFilter.ALL to "All", ScopeFilter.GLOBAL to "Global", ScopeFilter.PROJECT to "Project"), scopeFilter) {
            scopeFilter = it
            updateFiltersButton()
            renderTable()
        }
        if (scope is DetailsScope.ForProject) {
            addGroup(
                "Agent",
                listOf(null to "All agents") + lastComparison?.agents.orEmpty().map { it.id to it.name },
                agentFilter,
                agentChoices = true,
            ) {
                agentFilter = it
                updateFiltersButton()
                renderTable()
            }
        }
        return content
    }

    private fun showFiltersPopup() {
        val content = createFiltersContent()
        JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
            .setRequestFocus(true).setResizable(false).setMovable(false).createPopup()
            .showUnderneathOf(filtersButton)
    }

    private fun updateFiltersButton() {
        val active = (if (statusFilter == SkillFilter.ALL) 0 else 1) +
            (if (scope != null && scopeFilter != ScopeFilter.ALL) 1 else 0) +
            (if (scope is DetailsScope.ForProject && agentFilter != null) 1 else 0)
        filtersButton.text = if (active == 0) "Filters \u25be" else "Filters ($active) \u25be"
    }

    private fun selectedRow(): ComparisonRow? = comparisonModel.rowAt(comparisonTable.selectedRow)

    /** Skill -> Skills tab deep link; MCP config / instruction file -> the IDE editor. */
    private fun activate(row: ComparisonRow) {
        if (row.category == "Skill") showSkill(row) else openFile(row)
    }

    private fun showSkill(row: ComparisonRow) {
        val target = skillNavigationTarget(row, scope) ?: return
        openSkill(target.scope, target.skillId, target.sourcePath, target.project)
    }

    private fun openFile(row: ComparisonRow) =
        AgentHubFileActions.open(currentProject, row.sourcePath, "Open ${row.category.lowercase()}", MISSING_FILE)

    private fun contextMenu(row: ComparisonRow): JPopupMenu {
        val menu = JPopupMenu()
        val isSkill = row.category == "Skill"
        menu.add(JMenuItem(if (isSkill) "Show in Skills tab" else "Open").apply {
            isEnabled = if (isSkill) row.skillId != null else row.sourcePath != null
            addActionListener { activate(row) }
        })
        menu.add(JMenuItem("Reveal in Files").apply {
            isEnabled = row.sourcePath != null
            addActionListener {
                AgentHubFileActions.reveal(currentProject, row.sourcePath, "Reveal ${row.category.lowercase()}", MISSING_FILE)
            }
        })
        menu.add(JMenuItem("Copy Path").apply {
            isEnabled = row.sourcePath != null
            addActionListener { AgentHubFileActions.copyPath(row.sourcePath) }
        })
        return menu
    }

    private fun updateDetailStrip() {
        detailScroll.showLines(selectedRow()?.let(EnvironmentUiModel::detailLines).orEmpty())
        revalidate()
        repaint()
    }

    private fun fixedWidthCombo(options: Array<String>): JComboBox<String> = JComboBox(options).apply {
        val metrics = getFontMetrics(font)
        val width = options.maxOf { metrics.stringWidth(it) } + JBUI.scale(TYPE_FILTER_CHROME_WIDTH)
        val fixedSize = Dimension(width, preferredSize.height)
        preferredSize = fixedSize
        minimumSize = fixedSize
        maximumSize = fixedSize
    }

    private fun currentTypeFilter(): String = typeFilter.selectedItem as? String ?: "All"

    fun setProject(project: DiscoveredProject?) = setScope(project?.let { DetailsScope.ForProject(it) })

    fun setScope(scope: DetailsScope?) {
        if (disposed) return
        val generation = discoveryService.generation
        if (this.scope == scope && scopeGeneration == generation) return
        scopeGeneration = generation
        // A refresh of the SAME entity (shared-data change, focus-triggered reload) must not drop the
        // row the user just clicked; a different entity starts without a selection.
        retainedSelection = if (this.scope == scope) selectedRow()?.let(::rowIdentity) ?: retainedSelection else null
        if (this.scope != scope) AgentHubUiComponents.scrollToTop(comparisonTable)
        this.scope = scope
        val requestId = requestSequence.incrementAndGet()
        clearModel()
        val isAgent = scope is DetailsScope.ForAgent
        updateFiltersButton()
        comparisonTable.columnModel.getColumn(THIRD_COLUMN_INDEX).cellRenderer =
            if (isAgent) locationColumnRenderer else agentColumnRenderer
        comparisonModel.thirdColumnName = if (isAgent) "Location" else "Agent"
        when (scope) {
            null -> {
                summaryLabel.isVisible = false
                return
            }
            is DetailsScope.ForProject -> {
                val project = scope.project
                val cached = runCatching { EnvironmentIndexService.getInstance().cachedEnvironment(project.identity.id) }
                    .getOrNull()
                if (cached != null) {
                    render(cached.visibleTo(project.agents.mapTo(mutableSetOf()) { it.agentId }))
                } else {
                    summaryLabel.text = DISCOVERING
                    summaryLabel.isVisible = true
                }
                execute {
                    val result = runCatching { discoveryService.discover(project) }
                    deliver(requestId) {
                        result.fold(
                            onSuccess = { render(it) },
                            onFailure = { error -> failed(error) },
                        )
                    }
                }
            }
            is DetailsScope.ForAgent -> {
                val cached = runCatching { agentDiscoveryService.cached(scope.agentId, scope.projects) }.getOrNull()
                if (cached != null) {
                    render(cached, scope.agentId)
                } else {
                    summaryLabel.text = DISCOVERING
                    summaryLabel.isVisible = true
                }
                execute {
                    val result = runCatching { agentDiscoveryService.discover(scope.agentId, scope.projects) }
                    deliver(requestId) {
                        result.fold(
                            onSuccess = { render(it, scope.agentId) },
                            onFailure = { error -> failed(error) },
                        )
                    }
                }
            }
        }
    }

    private fun deliver(requestId: Long, callback: () -> Unit) {
        deliverResult {
            if (disposed || currentProject.isDisposed || requestSequence.get() != requestId || scopeGeneration != discoveryService.generation) return@deliverResult
            callback()
        }
    }

    private fun failed(error: Throwable) {
        summaryLabel.text = UserFacingError.describe("Environment discovery failed", error)
        summaryLabel.isVisible = true
        clearModel()
    }

    fun dispose() {
        disposed = true
        requestSequence.incrementAndGet()
    }

    private fun render(environment: ProjectEnvironment) {
        summaryLabel.text = ""
        summaryLabel.isVisible = false
        lastComparison = EnvironmentUiModel.comparison(environment, AgentHubUiComponents::displayName)
        if (agentFilter != null && lastComparison?.agents?.none { it.id == agentFilter } == true) {
            agentFilter = null
            updateFiltersButton()
        }
        renderTable()
    }

    private fun render(environment: AgentEnvironment, agentId: String) {
        summaryLabel.text = ""
        summaryLabel.isVisible = false
        lastComparison = EnvironmentUiModel.agentComparison(environment, agentId)
        renderTable()
    }

    private fun resetFilters() {
        statusFilter = SkillFilter.ALL
        scopeFilter = ScopeFilter.ALL
        agentFilter = null
        typeFilter.selectedItem = "All"
        renderTable()
    }

    private fun rowIdentity(row: ComparisonRow) = listOf(row.category, row.name, row.sourcePath, row.location, row.scope, row.skillId)

    private fun renderTable() {
        val selected = selectedRow()?.let(::rowIdentity) ?: retainedSelection
        val comparison = lastComparison
        if (comparison == null) {
            comparisonModel.setComparison(null)
            warningBar.setWarnings(emptyList())
            filterStatus.isVisible = false
            packColumns(null)
            return
        }
        warningBar.setWarnings(comparison.warnings)
        val type = currentTypeFilter()
        val status = statusFilter
        val scopeChoice = scopeFilter
        val filteredRows = EnvironmentUiModel.filterRows(
            comparison, type, status, scopeChoice,
            if (scope is DetailsScope.ForProject) agentFilter else null,
        )
        val filtered = comparison.copy(rows = filteredRows)
        agentColumnRenderer.agents = filtered.agents
        comparisonModel.setComparison(filtered)
        val selectedIndex = filteredRows.indexOfFirst { rowIdentity(it) == selected }
        if (selectedIndex >= 0) comparisonTable.setRowSelectionInterval(selectedIndex, selectedIndex)
        if (filteredRows.isNotEmpty()) retainedSelection = null
        val activeFilters = buildList {
            if (type != "All") add("Type: $type")
            if (status != SkillFilter.ALL) add("Status: $status")
            if (scopeChoice != ScopeFilter.ALL) add("Scope: $scopeChoice")
            if (scope is DetailsScope.ForProject && agentFilter != null) add("Agent: ${comparison.agents.firstOrNull { it.id == agentFilter }?.name ?: agentFilter}")
        }
        filterSummary.text = "${filteredRows.size} of ${comparison.rows.size} items" +
            activeFilters.takeIf { it.isNotEmpty() }?.joinToString(" · ", " · ").orEmpty()
        filterStatus.isVisible = activeFilters.isNotEmpty()
        clearFiltersButton.isVisible = activeFilters.isNotEmpty()
        comparisonTable.emptyText.text = if (comparison.rows.isNotEmpty() && filteredRows.isEmpty())
            "No environment items match these filters." else "No environment items discovered in this scope."
        updateFiltersButton()
        packColumns(filtered)
    }

    private fun clearModel() {
        filterStatus.isVisible = false
        lastComparison = null
        comparisonModel.setComparison(null)
        warningBar.setWarnings(emptyList())
    }

    private fun packColumns(comparison: EnvironmentComparison?) {
        val columnModel = comparisonTable.columnModel
        if (columnModel.columnCount < 3) return
        val metrics = comparisonTable.getFontMetrics(comparisonTable.font)

        val itemWidth = (comparison?.rows?.maxOfOrNull { metrics.stringWidth(it.name) } ?: 0)
            .coerceAtLeast(metrics.stringWidth("Item")) + TEXT_PADDING
        columnModel.getColumn(0).apply {
            minWidth = MIN_ITEM_WIDTH
            maxWidth = Int.MAX_VALUE
            preferredWidth = itemWidth
        }

        val typeWidth = (comparison?.rows?.maxOfOrNull { metrics.stringWidth(it.category) } ?: 0)
            .coerceAtLeast(metrics.stringWidth("Type")) + TEXT_PADDING
        columnModel.getColumn(1).apply {
            minWidth = MIN_COMPACT_COLUMN_WIDTH
            maxWidth = TYPE_COLUMN_MAX_WIDTH
            preferredWidth = typeWidth.coerceAtMost(TYPE_COLUMN_MAX_WIDTH)
        }

        val thirdWidth = if (scope is DetailsScope.ForAgent) {
            (comparison?.rows?.maxOfOrNull { metrics.stringWidth(it.location.orEmpty()) } ?: 0)
                .coerceAtLeast(metrics.stringWidth("Location")) + TEXT_PADDING
        } else {
            val maxIcons = comparison?.rows?.maxOfOrNull { row -> comparison.agents.count { it.id in row.agentIds } } ?: 0
            val iconsWidth = if (maxIcons > 0) maxIcons * (ICON_SIZE + ICON_GAP) + ICON_GAP else 0
            iconsWidth.coerceAtLeast(metrics.stringWidth("Agent") + TEXT_PADDING)
        }
        columnModel.getColumn(THIRD_COLUMN_INDEX).apply {
            minWidth = MIN_COMPACT_COLUMN_WIDTH
            maxWidth = if (scope is DetailsScope.ForAgent) LOCATION_COLUMN_MAX_WIDTH else AGENT_COLUMN_MAX_WIDTH
            preferredWidth = thirdWidth.coerceAtMost(maxWidth)
        }
    }

    private class AgentIconListCellRenderer(
        private val hover: TableHoverTracker,
    ) : TableCellRenderer {
        var agents: List<ComparisonAgent> = emptyList()
        private val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(ICON_GAP), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(AgentHubUiComponents.ROW_TEXT_PADDING)
        }
        private val wrapper = RoundedSelectionPanel.wrap(panel).apply {
            selectionArc = JBUI.scale(AgentHubUiComponents.SELECTION_ARC)
            selectionArcCorners = RoundedSelectionPanel.Corners.RIGHT
            selectionInsets = JBUI.insets(0, 0, 0, AgentHubUiComponents.SELECTION_HORIZONTAL_INSET)
        }

        @Suppress("UNCHECKED_CAST")
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val presentIds = value as? Set<String> ?: emptySet()
            panel.removeAll()
            agents.filter { it.id in presentIds }.forEach { agent ->
                val icon = AgentHubUiComponents.faviconFor(agent.id) ?: return@forEach
                panel.add(JLabel(icon).apply { toolTipText = agent.name; accessibleContext.accessibleName = agent.name })
            }
            wrapper.background = table.background
            wrapper.selectionColor = AgentHubUiComponents.rowHighlight(isSelected, hover.isHovered(row))
            return wrapper
        }
    }

    private class ComparisonTextCellRenderer(
        private val hover: TableHoverTracker,
        arcCorners: RoundedSelectionPanel.Corners,
        outerLeftInset: Int,
        outerRightInset: Int,
    ) : TableCellRenderer {
        private val label = JBLabel().apply {
            putClientProperty("html.disable", true)
            border = JBUI.Borders.empty(AgentHubUiComponents.ROW_TEXT_PADDING)
        }
        private val wrapper = RoundedSelectionPanel.wrap(label).apply {
            selectionArc = JBUI.scale(AgentHubUiComponents.SELECTION_ARC)
            selectionArcCorners = arcCorners
            selectionInsets = JBUI.insets(0, outerLeftInset, 0, outerRightInset)
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            label.text = value?.toString().orEmpty()
            wrapper.background = table.background
            val colors = AgentHubUiComponents.rowTextColors(table.foreground, isSelected)
            label.foreground = colors.foreground
            wrapper.selectionColor = AgentHubUiComponents.rowHighlight(isSelected, hover.isHovered(row))
            return wrapper
        }
    }

    private class EnvironmentComparisonTableModel : AbstractTableModel() {
        private var comparison: EnvironmentComparison? = null
        // Deliberately no fireTableStructureChanged(): that would rebuild the column model and
        // drop the custom cell renderers; the header cell is updated in place by the caller.
        var thirdColumnName: String = "Agent"

        fun setComparison(value: EnvironmentComparison?) {
            comparison = value
            fireTableDataChanged()
        }

        fun rowAt(index: Int): ComparisonRow? = comparison?.rows?.getOrNull(index)

        override fun getRowCount(): Int = comparison?.rows?.size ?: 0

        override fun getColumnCount(): Int = 3

        override fun getColumnName(column: Int): String = when (column) {
            0 -> "Item"
            1 -> "Type"
            else -> thirdColumnName
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val value = comparison?.rows?.getOrNull(rowIndex) ?: return ""
            return when (columnIndex) {
                0 -> value.name
                1 -> value.category
                else -> value.location ?: value.agentIds
            }
        }
    }

    private companion object {
        val TYPE_FILTER_OPTIONS = arrayOf("All", "Skill", "MCP", "Instruction", "Config")
        const val DISCOVERING = "Discovering environment…"
        const val MISSING_FILE = "The file is no longer available. Refresh the environment."
        const val ACTIVATE_ACTION = "agenthub.environment.activate"
        const val TYPE_FILTER_CHROME_WIDTH = 48
        const val THIRD_COLUMN_INDEX = 2
        const val ICON_SIZE = 16
        const val ICON_GAP = 4
        const val TEXT_PADDING = 24
        const val MIN_ITEM_WIDTH = 60
        val MIN_COMPACT_COLUMN_WIDTH = JBUI.scale(32)
        val TYPE_COLUMN_MAX_WIDTH = JBUI.scale(92)
        val AGENT_COLUMN_MAX_WIDTH = JBUI.scale(88)
        val LOCATION_COLUMN_MAX_WIDTH = JBUI.scale(120)
    }
}

internal data class SkillNavigationTarget(
    val scope: SkillScope,
    val skillId: String,
    val sourcePath: String?,
    val project: DiscoveredProject?,
)

/** Project Environment includes global skills, so the row's own scope determines the destination. */
internal fun skillNavigationTarget(row: ComparisonRow, current: DetailsScope?): SkillNavigationTarget? {
    if (row.category != "Skill") return null
    val skillId = row.skillId ?: return null
    val skillScope = row.skillScope ?: return null
    val project = if (skillScope == SkillScope.PROJECT) (current as? DetailsScope.ForProject)?.project else null
    return when (current) {
        is DetailsScope.ForProject -> SkillNavigationTarget(skillScope, skillId, row.sourcePath, project)
        is DetailsScope.ForAgent -> SkillNavigationTarget(skillScope, skillId, row.sourcePath, null)
        null -> null
    }
}
