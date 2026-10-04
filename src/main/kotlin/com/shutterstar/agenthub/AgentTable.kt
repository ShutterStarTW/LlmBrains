package com.shutterstar.agenthub

import com.intellij.ui.JBColor
import com.intellij.ui.table.JBTable
import com.shutterstar.agenthub.projects.ui.AgentHubUiComponents
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GridBagLayout
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableRowSorter

/**
 * [DefaultTableCellRenderer.setBackground] remembers the last colour as the renderer's "unselected"
 * background. [JBTable] applies the hover colour AFTER rendering (prepareRenderer), so with a shared
 * renderer instance that hover colour leaked into every following row of the column. Clearing it
 * before each render makes the renderer fall back to the table background again.
 */
internal open class HoverSafeCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        background = null
        return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
    }
}

internal data class AgentRow(val agent: CodingAgent, var enabled: Boolean)

/**
 * Everything [AgentTable] needs from its owning [AgentSettingsConfigurable] that isn't fixed at
 * construction time — detection results, the outdated set and the spinner frame are all reassigned
 * wholesale after a detect/update cycle, so these are read through accessor lambdas (evaluated
 * fresh on every render) rather than captured as snapshots.
 */
internal class AgentTableContext(
    val detectedInstalled: () -> Map<String, Boolean>,
    val outdatedAgents: () -> Set<String>,
    val unverifiedAgents: () -> Set<String>,
    val inProgressAgentIds: () -> Set<String>,
    val spinnerFrame: () -> Int,
    val spinnerFrames: Array<String>,
    val onActionClick: (CodingAgent) -> Unit,
)

/**
 * One agent/companion table: model + [JBTable] + its own hover state. Extracted from
 * [AgentSettingsConfigurable] (formerly a private inner class) purely to keep that file's size
 * down — behavior is unchanged, shared mutable state (detection results, spinner, in-progress set)
 * now flows in through [AgentTableContext] instead of implicit outer-class access.
 */
internal class AgentTable(val rows: List<AgentRow>, private val context: AgentTableContext) {
    private var linkHoverRow = -1
    private var linkHoverCol = -1
    private var buttonHoverRow = -1
    private var buttonPointerRow = -1

    // [row]/[col] are VIEW coordinates (from rowAtPoint/columnAtPoint); the model lookup needs
    // the model row, while the pixel rect must stay in view space — the row filter (search box)
    // means the two can now diverge.
    private fun isOverLinkText(e: MouseEvent, row: Int, col: Int): Boolean {
        val text = tableModel.getValueAt(table.convertRowIndexToModel(row), col).toString()
        if (text.isBlank() || text == "—") return false
        return table.getCellRect(row, col, false).contains(e.point)
    }

    val tableModel = object : AbstractTableModel() {
        val columns = arrayOf("", "Agent", "Provider", "Status", "Action", "Website", "Source")
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 7
        override fun getColumnName(col: Int) = columns[col]
        override fun getColumnClass(col: Int) = if (col == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, col: Int) = col == 0
        override fun getValueAt(row: Int, col: Int): Any {
            val agent = rows[row].agent
            val isInstalled = context.detectedInstalled()[agent.id]
            return when (col) {
                0 -> rows[row].enabled
                1 -> agent.name
                2 -> agent.provider
                3 -> when {
                    isInstalled == null -> ""
                    isInstalled && agent.id in context.outdatedAgents() -> "↑"
                    isInstalled && agent.id in context.unverifiedAgents() -> "?"
                    isInstalled -> "✓"
                    else -> "✗"
                }
                4 -> when {
                    isInstalled == null -> ""
                    isInstalled && agent.id in context.outdatedAgents() && agent.updateHint.isNotBlank() -> "Update"
                    isInstalled && agent.platformUninstallHint.isNotBlank() -> "Remove"
                    !isInstalled && agent.platformInstallHint.isNotBlank() -> "Install"
                    else -> ""
                }
                5 -> extractDomain(agent.url)
                6 -> extractDomain(agent.devUrl)
                else -> ""
            }
        }
        override fun setValueAt(value: Any?, row: Int, col: Int) {
            if (col == 0 && value is Boolean) {
                rows[row].enabled = value
                fireTableCellUpdated(row, col)
            }
        }
    }

    val table = object : JBTable(tableModel) {
        private var actionMouseEvent = false

        override fun getHoveredRowBackground(): Color? =
            if (buttonPointerRow >= 0) null else super.getHoveredRowBackground()

        override fun processMouseEvent(event: MouseEvent) {
            val column = columnAtPoint(event.point)
            val row = rowAtPoint(event.point)
            actionMouseEvent = SwingUtilities.isLeftMouseButton(event) && column >= 0 && row >= 0 &&
                convertColumnIndexToModel(column) == 4 && isOverActionButton(event, row)
            try { super.processMouseEvent(event) } finally { actionMouseEvent = false }
        }

        override fun changeSelection(rowIndex: Int, columnIndex: Int, toggle: Boolean, extend: Boolean) {
            if (!actionMouseEvent) super.changeSelection(rowIndex, columnIndex, toggle, extend)
        }
        override fun getToolTipText(e: MouseEvent): String? {
            val viewRow = rowAtPoint(e.point)
            val viewCol = columnAtPoint(e.point)
            if (viewRow < 0 || viewCol < 0 || convertColumnIndexToModel(viewCol) != 3) return super.getToolTipText(e)
            return when (getValueAt(viewRow, viewCol)) {
                "↑" -> "Update available"
                "?" -> "Installed - update status could not be verified (no version source, or the lookup failed)"
                "✓" -> "Installed"
                "✗" -> "Not installed"
                else -> null
            }
        }
    }.apply {
        setShowGrid(false)
        intercellSpacing = Dimension(0, JBUI.scale(AgentHubUiComponents.SELECTION_VERTICAL_INSET))
        columnSelectionAllowed = false
        // Selectable rows give the table a keyboard focus path: arrow keys move the selection,
        // and Space/Enter (bound below) toggle the row's checkbox / trigger its Action button —
        // previously the whole table was mouse-only.
        rowSelectionAllowed = true
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        tableHeader.reorderingAllowed = false

        // Row-only filtering (search box) — sorting itself stays off so row order never changes.
        val sorter = TableRowSorter(tableModel)
        for (col in 0 until tableModel.columnCount) sorter.setSortable(col, false)
        rowSorter = sorter

        columnModel.getColumn(0).apply {
            maxWidth = JBUI.scale(30); minWidth = JBUI.scale(30)
            cellRenderer = object : HoverSafeCellRenderer() {
                private val checkbox = JCheckBox().apply { isOpaque = true }
                override fun getTableCellRendererComponent(
                    t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int,
                ): Component {
                    checkbox.isSelected = value as? Boolean ?: false
                    checkbox.background = if (sel) t.selectionBackground else t.background
                    checkbox.accessibleContext.accessibleName = "Show ${rows[t.convertRowIndexToModel(row)].agent.name} in toolbar"
                    checkbox.border = null
                    return checkbox
                }
            }
        }
        columnModel.getColumn(1).apply { preferredWidth = JBUI.scale(120) }
        columnModel.getColumn(2).apply { preferredWidth = JBUI.scale(100) }
        columnModel.getColumn(3).apply { preferredWidth = JBUI.scale(58); maxWidth = JBUI.scale(68) }
        columnModel.getColumn(4).apply { minWidth = JBUI.scale(70); maxWidth = JBUI.scale(70); preferredWidth = JBUI.scale(70) }
        columnModel.getColumn(5).apply { preferredWidth = JBUI.scale(130) }
        columnModel.getColumn(6).apply { preferredWidth = JBUI.scale(130) }

        columnModel.getColumn(1).cellRenderer = object : HoverSafeCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int,
            ): Component {
                val c = super.getTableCellRendererComponent(t, value, sel, false, row, col) as JLabel
                c.border = JBUI.Borders.emptyLeft(AgentHubUiComponents.CONTROL_GAP)
                val agent = rows[t.convertRowIndexToModel(row)].agent
                c.icon = FaviconLoader.get(agent)
                c.iconTextGap = JBUI.scale(AgentHubUiComponents.SMALL_GAP)
                return c
            }
        }

        columnModel.getColumn(2).cellRenderer = object : HoverSafeCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int,
            ): Component = super.getTableCellRendererComponent(t, value, sel, false, row, col).apply {
                (this as JLabel).border = null
            }
        }

        columnModel.getColumn(3).cellRenderer = object : HoverSafeCellRenderer() {
            private val installedColor = JBColor.namedColor("Label.successForeground", JBColor(Color(0, 128, 0), Color(98, 198, 98)))
            private val outdatedColor = JBColor.namedColor("Label.warningForeground", JBColor(Color(180, 100, 0), Color(220, 160, 60)))
            private val notInstalledColor = JBColor.namedColor("Label.disabledForeground", JBColor.GRAY)

            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int,
            ): Component {
                val c = super.getTableCellRendererComponent(t, value, sel, false, row, col) as JLabel
                c.border = null
                c.horizontalAlignment = CENTER
                c.accessibleContext.accessibleName = statusDescription(value)
                c.foreground = if (sel) t.selectionForeground else when (value?.toString()) {
                    "✓" -> installedColor
                    "↑" -> outdatedColor
                    "✗", "?" -> notInstalledColor
                    else -> t.foreground
                }
                return c
            }
        }

        // Keep the native button at its preferred size inside the Action cell.
        columnModel.getColumn(4).cellRenderer = object : TableCellRenderer {
            private val button = object : JButton() {
                var rendererFocused = false
                override fun hasFocus(): Boolean = rendererFocused
            }.apply {
                isFocusable = false
                isRolloverEnabled = true
                margin = JBUI.insets(1, AgentHubUiComponents.CONTROL_GAP)
            }
            private val container = JPanel(GridBagLayout()).apply { add(button) }
            private val empty = JPanel().apply { isOpaque = false }

            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int,
            ): Component {
                val agent = rows[t.convertRowIndexToModel(row)].agent
                val text = value?.toString().orEmpty()
                val inProgress = agent.id in context.inProgressAgentIds()
                if (text.isBlank() && !inProgress) return empty
                button.font = t.font
                button.isEnabled = !inProgress
                button.text = if (inProgress) "${context.spinnerFrames[context.spinnerFrame()]} $text" else text
                button.model.isRollover = !inProgress && row == buttonHoverRow
                button.rendererFocused = focus
                button.background = if (sel) t.selectionBackground else javax.swing.UIManager.getColor("Button.background") ?: t.background
                button.foreground = if (sel) t.selectionForeground else javax.swing.UIManager.getColor("Button.foreground") ?: t.foreground
                button.toolTipText = if (inProgress) "Operation in progress" else null
                button.accessibleContext.accessibleName = if (inProgress) "Operation in progress" else "$text ${agent.name}"
                container.background = if (sel) t.selectionBackground else t.background
                return container
            }
        }

        val linkRenderer = object : HoverSafeCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int,
            ): Component {
                val c = super.getTableCellRendererComponent(t, value, sel, false, row, col) as JLabel
                // Extra left padding on the Website column so its text doesn't crowd the Action button beside it
                c.border = if (col == 5) JBUI.Borders.emptyLeft(AgentHubUiComponents.PANEL_INSET) else null
                val text = value?.toString().orEmpty()
                if (text.isNotBlank()) {
                    c.foreground = if (sel) t.selectionForeground else JBUI.CurrentTheme.Link.Foreground.ENABLED
                    val isHover = row == linkHoverRow && col == linkHoverCol
                    c.text = if (isHover) "<html><u>$text</u></html>" else text
                } else {
                    c.foreground = t.foreground
                    c.text = "—"
                }
                return c
            }
        }
        columnModel.getColumn(5).cellRenderer = linkRenderer
        columnModel.getColumn(6).cellRenderer = linkRenderer

        com.shutterstar.agenthub.projects.ui.RowContextMenus.install(this) { index ->
            val agent = rows[index].agent
            javax.swing.JPopupMenu().apply {
                add(javax.swing.JMenuItem("Open website").apply {
                    isEnabled = agent.url.isNotBlank()
                    addActionListener { com.intellij.ide.BrowserUtil.browse(agent.url) }
                })
                add(javax.swing.JMenuItem("Open source").apply {
                    isEnabled = agent.devUrl.isNotBlank()
                    addActionListener { com.intellij.ide.BrowserUtil.browse(agent.devUrl) }
                })
                add(javax.swing.JMenuItem("Copy install command").apply {
                    isEnabled = agent.platformInstallHint.isNotBlank()
                    addActionListener { com.shutterstar.agenthub.projects.ui.AgentHubFileActions.copyPath(agent.platformInstallHint) }
                })
                addSeparator()
                add(javax.swing.JMenuItem(statusDescription(tableModel.getValueAt(index, 3))).apply { isEnabled = false })
            }
        }
        cursor = Cursor(Cursor.DEFAULT_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val col = columnAtPoint(e.point)
                val viewRow = rowAtPoint(e.point)
                if (viewRow < 0) return
                val row = convertRowIndexToModel(viewRow)
                val agent = rows[row].agent
                when (col) {
                    1 -> { rows[row].enabled = !rows[row].enabled; tableModel.fireTableCellUpdated(row, 0) }
                    4 -> if (isOverActionButton(e, viewRow) && agent.id !in context.inProgressAgentIds()) context.onActionClick(agent)
                    5 -> if (isOverLinkText(e, viewRow, col)) agent.url.takeIf { it.isNotBlank() }?.let { com.intellij.ide.BrowserUtil.browse(it) }
                    6 -> if (isOverLinkText(e, viewRow, col)) agent.devUrl.takeIf { it.isNotBlank() }?.let { com.intellij.ide.BrowserUtil.browse(it) }
                }
            }
            override fun mouseExited(e: MouseEvent) {
                val oldLinkRow = linkHoverRow; val oldLinkCol = linkHoverCol
                linkHoverRow = -1; linkHoverCol = -1
                if (oldLinkRow >= 0) repaint(getCellRect(oldLinkRow, oldLinkCol, false))
                buttonHoverRow = -1
                buttonPointerRow = -1
                revalidate()
                repaint()
            }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val col = columnAtPoint(e.point)
                val row = rowAtPoint(e.point)
                val modelRow = if (row >= 0) convertRowIndexToModel(row) else -1
                val overButton = col == 4 && row >= 0 && isOverActionButton(e, row)
                val actionActive = overButton && rows[modelRow].agent.id !in context.inProgressAgentIds()
                val overLink = row >= 0 && (col == 5 || col == 6) && isOverLinkText(e, row, col)
                cursor = if (actionActive || overLink)
                    Cursor(Cursor.HAND_CURSOR)
                else
                    Cursor(Cursor.DEFAULT_CURSOR)
                val newHoverRow = if (overLink) row else -1
                val newHoverCol = if (overLink) col else -1
                if (newHoverRow != linkHoverRow || newHoverCol != linkHoverCol) {
                    val oldRow = linkHoverRow; val oldCol = linkHoverCol
                    linkHoverRow = newHoverRow; linkHoverCol = newHoverCol
                    if (oldRow >= 0) repaint(getCellRect(oldRow, oldCol, false))
                    if (newHoverRow >= 0) repaint(getCellRect(newHoverRow, newHoverCol, false))
                }
                val newButtonHover = if (actionActive) row else -1
                val newButtonPointer = if (overButton) row else -1
                if (newButtonHover != buttonHoverRow || newButtonPointer != buttonPointerRow) {
                    buttonHoverRow = newButtonHover
                    buttonPointerRow = newButtonPointer
                    revalidate()
                    repaint()
                }
            }
        })

        // Space toggles the selected row's checkbox, Enter triggers its Action button (if any) —
        // the mouse-only click handlers above cover the same actions, this just gives the table
        // an equivalent keyboard path once a row is focused/selected.
        inputMap.put(KeyStroke.getKeyStroke("SPACE"), "agenthub-toggle-enabled")
        actionMap.put(
            "agenthub-toggle-enabled",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    val row = selectedRow.takeIf { it >= 0 }?.let { convertRowIndexToModel(it) } ?: return
                    rows[row].enabled = !rows[row].enabled
                    tableModel.fireTableCellUpdated(row, 0)
                }
            },
        )
        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "agenthub-trigger-action")
        actionMap.put(
            "agenthub-trigger-action",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    val row = selectedRow.takeIf { it >= 0 }?.let { convertRowIndexToModel(it) } ?: return
                    if (rows[row].agent.id !in context.inProgressAgentIds() && tableModel.getValueAt(row, 4).toString().isNotBlank()) context.onActionClick(rows[row].agent)
                }
            },
        )
    }

    init {
        updateTableMetrics()
        table.addPropertyChangeListener("font") { updateTableMetrics() }
        table.addPropertyChangeListener("UI") { updateTableMetrics() }
    }

    private fun statusDescription(value: Any?): String = when (value?.toString()) {
        "✓" -> "Installed"
        "✗" -> "Not installed"
        "↑" -> "Update available"
        "?" -> "Installed; update status could not be verified"
        else -> "Installation status unknown; run detection"
    }

    private fun isOverActionButton(event: MouseEvent, viewRow: Int): Boolean =
        actionButtonBounds(viewRow)?.contains(event.point) == true

    internal fun actionButtonBounds(viewRow: Int): Rectangle? {
        val text = table.getValueAt(viewRow, 4).toString()
        val agentId = rows[table.convertRowIndexToModel(viewRow)].agent.id
        if (text.isBlank() && agentId !in context.inProgressAgentIds()) return null
        val renderer = table.getCellRenderer(viewRow, 4)
        val container = renderer.getTableCellRendererComponent(table, text, false, false, viewRow, 4) as JPanel
        val size = (container.getComponent(0) as JButton).preferredSize
        val cell = table.getCellRect(viewRow, 4, false)
        val width = minOf(size.width, cell.width)
        val height = minOf(size.height, cell.height)
        return Rectangle(cell.x + (cell.width - width) / 2, cell.y + (cell.height - height) / 2, width, height)
    }

    private fun updateTableMetrics() {
        val actionSize = listOf("Install", "Update", "Remove").flatMap { text ->
            listOf(text, "${context.spinnerFrames.maxBy { it.length }} $text")
        }.map { text ->
            JButton(text).apply {
                font = table.font
                margin = JBUI.insets(1, AgentHubUiComponents.CONTROL_GAP)
            }.preferredSize
        }
        val textHeight = table.getFontMetrics(table.font).height + JBUI.scale(AgentHubUiComponents.SMALL_GAP * 2)
        table.rowHeight = maxOf(textHeight, actionSize.maxOf { it.height }, JCheckBox().preferredSize.height) +
            table.intercellSpacing.height
        val actionWidth = maxOf(JBUI.scale(70), actionSize.maxOf { it.width } + JBUI.scale(AgentHubUiComponents.SMALL_GAP))
        table.columnModel.getColumn(4).apply {
            maxWidth = Int.MAX_VALUE
            minWidth = actionWidth
            preferredWidth = actionWidth
            maxWidth = actionWidth
        }
    }

    // Repaint the Action cell of any in-progress row so the spinner animates.
    fun repaintInProgress() {
        context.inProgressAgentIds().forEach { id ->
            rows.indexOfFirst { it.agent.id == id }.takeIf { it >= 0 }
                ?.let { table.convertRowIndexToView(it) }
                ?.takeIf { it >= 0 }
                ?.let { table.repaint(table.getCellRect(it, 4, false)) }
        }
    }

    // Filters rows to those whose agent name or provider contains [query] (case-insensitive).
    // Sorting itself is disabled on the sorter (see rowSorter setup above), so this only ever
    // hides/shows rows, never reorders them.
    fun applyFilter(query: String) {
        val trimmed = query.trim()
        @Suppress("UNCHECKED_CAST")
        val sorter = table.rowSorter as? TableRowSorter<AbstractTableModel>
        sorter?.rowFilter = if (trimmed.isEmpty()) {
            null
        } else {
            RowFilter.regexFilter("(?i)" + Regex.escape(trimmed), 1, 2)
        }
    }

    private fun extractDomain(url: String): String = if (url.isBlank()) "" else try {
        URI(url).host?.removePrefix("www.") ?: url
    } catch (_: Exception) {
        url
    }
}
