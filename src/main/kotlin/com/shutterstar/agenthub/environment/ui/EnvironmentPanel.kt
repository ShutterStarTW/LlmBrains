package com.shutterstar.agenthub.environment.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.environment.discovery.ProjectEnvironmentDiscoveryService
import com.shutterstar.agenthub.environment.model.ProjectEnvironment
import com.shutterstar.agenthub.environment.persistence.EnvironmentIndexService
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.ui.AgentHubUiComponents
import com.shutterstar.agenthub.projects.ui.RoundedSelectionPanel
import com.shutterstar.agenthub.projects.ui.TableHoverTracker
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicLong
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer

class EnvironmentPanel(
    private val currentProject: Project,
    private val discoveryService: ProjectEnvironmentDiscoveryService = ProjectEnvironmentDiscoveryService(),
) : JPanel(BorderLayout()) {
    private val summaryLabel = JBLabel("Select a project to inspect its environment")
    private val typeFilter = JComboBox(TYPE_FILTER_OPTIONS).apply {
        val metrics = getFontMetrics(font)
        val width = TYPE_FILTER_OPTIONS.maxOf { metrics.stringWidth(it) } + TYPE_FILTER_CHROME_WIDTH
        val fixedSize = Dimension(width, preferredSize.height)
        preferredSize = fixedSize
        minimumSize = fixedSize
        maximumSize = fixedSize
    }
    private val statusFilterAll = JRadioButton("All", true)
    private val statusFilterShared = JRadioButton("Shared")
    private val statusFilterConflicts = JRadioButton("Conflicts")
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
            return (deepest as? JComponent)?.toolTipText
        }
    }
    private val tableHover = TableHoverTracker(comparisonTable)
    private val agentColumnRenderer = AgentIconListCellRenderer(tableHover)
    private val requestSequence = AtomicLong()
    private var lastComparison: EnvironmentComparison? = null

    init {
        border = JBUI.Borders.empty(AgentHubUiComponents.PANEL_INSET, 0, 0, 0)
        summaryLabel.border = JBUI.Borders.empty(
            0,
            AgentHubUiComponents.TEXT_LEFT_INSET,
            AgentHubUiComponents.CONTROL_GAP,
            0,
        )
        summaryLabel.font = summaryLabel.font.deriveFont(Font.BOLD)
        comparisonTable.fillsViewportHeight = true
        comparisonTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        comparisonTable.showVerticalLines = false
        comparisonTable.showHorizontalLines = false
        comparisonTable.intercellSpacing = Dimension(0, AgentHubUiComponents.SELECTION_VERTICAL_INSET * 2)
        comparisonTable.tableHeader.defaultRenderer = ComparisonHeaderRenderer()
        comparisonTable.columnModel.getColumn(0).cellRenderer = ComparisonTextCellRenderer(
            tableHover,
            RoundedSelectionPanel.Corners.LEFT,
            AgentHubUiComponents.SELECTION_HORIZONTAL_INSET,
            0,
        )
        comparisonTable.columnModel.getColumn(1).cellRenderer =
            ComparisonTextCellRenderer(tableHover, RoundedSelectionPanel.Corners.NONE, 0, 0)
        comparisonTable.columnModel.getColumn(AGENT_COLUMN_INDEX).cellRenderer = agentColumnRenderer
        ToolTipManager.sharedInstance().registerComponent(comparisonTable)
        val header = JPanel(BorderLayout())
        header.add(summaryLabel, BorderLayout.NORTH)
        header.add(filterBar(), BorderLayout.SOUTH)
        add(header, BorderLayout.NORTH)
        add(AgentHubUiComponents.alignedBorderlessScrollPane(comparisonTable), BorderLayout.CENTER)
    }

    private fun filterBar(): JPanel {
        val group = ButtonGroup()
        val bar = JPanel()
        bar.layout = BoxLayout(bar, BoxLayout.X_AXIS)
        bar.border = JBUI.Borders.empty(
            0,
            AgentHubUiComponents.TEXT_LEFT_INSET,
            AgentHubUiComponents.CONTROL_GAP,
            0,
        )
        bar.add(JBLabel("Type:"))
        bar.add(typeFilter)
        bar.add(Box.createHorizontalStrut(JBUI.scale(AgentHubUiComponents.TEXT_LEFT_INSET)))
        listOf(statusFilterAll, statusFilterShared, statusFilterConflicts).forEach { button ->
            group.add(button)
            button.addActionListener { renderTable() }
            bar.add(button)
        }
        typeFilter.addActionListener { renderTable() }
        return bar
    }

    private fun currentTypeFilter(): String = typeFilter.selectedItem as? String ?: "All"

    private fun currentStatusFilter(): SkillFilter = when {
        statusFilterShared.isSelected -> SkillFilter.SHARED
        statusFilterConflicts.isSelected -> SkillFilter.CONFLICTS
        else -> SkillFilter.ALL
    }

    fun setProject(project: DiscoveredProject?) {
        val requestId = requestSequence.incrementAndGet()
        clearModel()
        if (project == null) {
            summaryLabel.text = "Select a project to inspect its environment"
            return
        }

        val cached = runCatching { EnvironmentIndexService.getInstance().cachedEnvironment(project.identity.id) }
            .getOrNull()
        if (cached != null) {
            render(cached)
            summaryLabel.text = "${summaryLabel.text} (cached, refreshing…)"
        } else {
            summaryLabel.text = "Discovering environment…"
        }
        AppExecutorUtil.getAppExecutorService().submit {
            val result = runCatching { discoveryService.discover(project) }
            ApplicationManager.getApplication().invokeLater {
                if (currentProject.isDisposed || requestSequence.get() != requestId) return@invokeLater
                result.fold(
                    onSuccess = ::render,
                    onFailure = { error ->
                        summaryLabel.text = "Environment discovery failed: ${error.javaClass.simpleName}"
                        clearModel()
                    },
                )
            }
        }
    }

    private fun render(environment: ProjectEnvironment) {
        val summary = EnvironmentUiModel.summary(environment)
        summaryLabel.text =
            "Skills ${summary.skillCount} (${summary.skillConflictCount} conflicts) · " +
            "MCP ${summary.mcpServerCount} (${summary.mcpConflictCount} conflicts) · " +
            "Instructions ${summary.instructionCount} · Warnings ${summary.warningCount}"
        lastComparison = EnvironmentUiModel.comparison(environment, AgentHubUiComponents::displayName)
        renderTable()
    }

    private fun renderTable() {
        val comparison = lastComparison
        if (comparison == null) {
            comparisonModel.setComparison(null)
            packColumns(null)
            return
        }
        val type = currentTypeFilter()
        val status = currentStatusFilter()
        val filteredRows = comparison.rows.filter { row ->
            (type == "All" || row.category == type) &&
                when (status) {
                    SkillFilter.ALL -> true
                    SkillFilter.SHARED -> row.shared
                    SkillFilter.CONFLICTS -> row.conflict
                }
        }
        val filtered = comparison.copy(rows = filteredRows)
        agentColumnRenderer.agents = filtered.agents
        comparisonModel.setComparison(filtered)
        packColumns(filtered)
    }

    private fun clearModel() {
        lastComparison = null
        comparisonModel.setComparison(null)
    }

    private fun packColumns(comparison: EnvironmentComparison?) {
        val columnModel = comparisonTable.columnModel
        if (columnModel.columnCount < 3) return
        val metrics = comparisonTable.getFontMetrics(comparisonTable.font)

        val typeWidth = (comparison?.rows?.maxOfOrNull { metrics.stringWidth(it.category) } ?: 0)
            .coerceAtLeast(metrics.stringWidth("Type")) + TEXT_PADDING
        columnModel.getColumn(0).apply {
            minWidth = typeWidth
            maxWidth = typeWidth
            preferredWidth = typeWidth
        }

        val itemWidth = (comparison?.rows?.maxOfOrNull { metrics.stringWidth(it.name) } ?: 0)
            .coerceAtLeast(metrics.stringWidth("Item")) + TEXT_PADDING
        columnModel.getColumn(1).apply {
            minWidth = MIN_ITEM_WIDTH
            maxWidth = itemWidth.coerceAtLeast(MIN_ITEM_WIDTH)
            preferredWidth = itemWidth
        }

        val maxIcons = comparison?.rows?.maxOfOrNull { row -> comparison.agents.count { it.id in row.agentIds } } ?: 0
        val iconsWidth = if (maxIcons > 0) maxIcons * (ICON_SIZE + ICON_GAP) + ICON_GAP else 0
        val agentWidth = iconsWidth.coerceAtLeast(metrics.stringWidth("Agent") + TEXT_PADDING)
        columnModel.getColumn(AGENT_COLUMN_INDEX).apply {
            minWidth = agentWidth
            maxWidth = Int.MAX_VALUE
            preferredWidth = agentWidth
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
            selectionArc = AgentHubUiComponents.SELECTION_ARC
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
                panel.add(JLabel(icon).apply { toolTipText = agent.name })
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
            border = JBUI.Borders.empty(AgentHubUiComponents.ROW_TEXT_PADDING)
        }
        private val wrapper = RoundedSelectionPanel.wrap(label).apply {
            selectionArc = AgentHubUiComponents.SELECTION_ARC
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

    private class ComparisonHeaderRenderer : JLabel(), TableCellRenderer {
        init {
            horizontalAlignment = LEFT
            isOpaque = true
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            text = value?.toString().orEmpty()
            font = table.tableHeader.font
            foreground = table.tableHeader.foreground
            background = table.tableHeader.background
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(0, AgentHubUiComponents.LIST_ITEM_LEFT_INSET),
            )
            return this
        }
    }

    private class EnvironmentComparisonTableModel : AbstractTableModel() {
        private var comparison: EnvironmentComparison? = null

        fun setComparison(value: EnvironmentComparison?) {
            comparison = value
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = comparison?.rows?.size ?: 0

        override fun getColumnCount(): Int = 3

        override fun getColumnName(column: Int): String = when (column) {
            0 -> "Type"
            1 -> "Item"
            else -> "Agent"
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val value = comparison?.rows?.getOrNull(rowIndex) ?: return ""
            return when (columnIndex) {
                0 -> value.category
                1 -> value.name
                else -> value.agentIds
            }
        }
    }

    private companion object {
        val TYPE_FILTER_OPTIONS = arrayOf("All", "Skill", "MCP", "Instruction", "Warning")
        const val TYPE_FILTER_CHROME_WIDTH = 48
        const val AGENT_COLUMN_INDEX = 2
        const val ICON_SIZE = 16
        const val ICON_GAP = 4
        const val TEXT_PADDING = 24
        const val MIN_ITEM_WIDTH = 60
    }
}
