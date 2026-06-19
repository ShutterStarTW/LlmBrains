package com.shutterstar.agenthub

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class AgentSettingsConfigurable : Configurable {

    private data class AgentRow(val agent: CodingAgent, var enabled: Boolean)

    private val rows: List<AgentRow> = CodingAgents.available().map { AgentRow(it, false) }

    // Detection results: null = not yet checked, true/false = result
    private var detectedInstalled: Map<String, Boolean> = AgentSettingsState.getInstance().getDetectionResults() ?: emptyMap()
    private var outdatedAgents: Set<String> = AgentSettingsState.getInstance().getOutdatedAgentIds()
    private var linkHoverRow = -1
    private var linkHoverCol = -1
    private var buttonHoverRow = -1

    // Agents with an install/update/remove in flight; their Action cell shows an animated spinner.
    private val inProgressAgentIds = mutableSetOf<String>()
    private val spinnerFrames = arrayOf("◐", "◓", "◑", "◒")
    private var spinnerFrame = 0
    private val spinnerTimer = javax.swing.Timer(130) {
        spinnerFrame = (spinnerFrame + 1) % spinnerFrames.size
        inProgressAgentIds.forEach { id ->
            rows.indexOfFirst { it.agent.id == id }.takeIf { it >= 0 }
                ?.let { table.repaint(table.getCellRect(it, 4, false)) }
        }
    }

    // Action button colors derived from the theme's default-button look (not a fixed blue).
    private val actionNormalBg = javax.swing.UIManager.getColor("Button.background") ?: JBColor.background()
    private val actionHoverBg = javax.swing.UIManager.getColor("Button.default.startBackground")
        ?: javax.swing.UIManager.getColor("Component.focusColor")
        ?: JBColor(Color(197, 213, 239), Color(82, 99, 125))
    private val actionHoverFg: Color? = javax.swing.UIManager.getColor("Button.default.foreground")
    private val actionNormalBorderColor: Color = JBColor.border()
    private val actionHoverBorderColor: Color = javax.swing.UIManager.getColor("Button.default.focusedBorderColor")
        ?: javax.swing.UIManager.getColor("Button.default.startBorderColor")
        ?: actionNormalBorderColor

    private fun isOverLinkText(e: MouseEvent, row: Int, col: Int): Boolean {
        val text = tableModel.getValueAt(row, col).toString()
        if (text.isBlank() || text == "—") return false
        val cellRect = table.getCellRect(row, col, false)
        val textWidth = table.getFontMetrics(table.font).stringWidth(text)
        return e.x in cellRect.x..(cellRect.x + 2 + textWidth)
    }

    private val tableModel = object : AbstractTableModel() {
        val columns = arrayOf("", "Agent", "Provider", "Status", "Action", "Website", "Source")
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 7
        override fun getColumnName(col: Int) = columns[col]
        override fun getColumnClass(col: Int) = if (col == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, col: Int) = col == 0
        override fun getValueAt(row: Int, col: Int): Any {
            val agent = rows[row].agent
            val isInstalled = detectedInstalled[agent.id]
            return when (col) {
                0 -> rows[row].enabled
                1 -> agent.name
                2 -> agent.provider
                3 -> when {
                    isInstalled == null -> ""
                    isInstalled && agent.id in outdatedAgents -> "↑"
                    isInstalled -> "✓"
                    else -> "✗"
                }
                4 -> when {
                    isInstalled == null -> ""
                    isInstalled && agent.id in outdatedAgents && agent.updateHint.isNotBlank() -> "Update"
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

    private val table = object : JBTable(tableModel) {
        override fun prepareRenderer(renderer: javax.swing.table.TableCellRenderer, row: Int, column: Int): Component {
            val c = super.prepareRenderer(renderer, row, column)
            if (convertColumnIndexToModel(column) == 4 && row == buttonHoverRow) {
                c.background = this@AgentSettingsConfigurable.actionHoverBg
            }
            return c
        }
    }.apply {
        setShowGrid(false)
        intercellSpacing = java.awt.Dimension(0, 2)
        rowHeight = 22
        columnSelectionAllowed = false
        rowSelectionAllowed = false

        columnModel.getColumn(0).apply {
            maxWidth = 30; minWidth = 30
            cellRenderer = object : DefaultTableCellRenderer() {
                private val checkbox = javax.swing.JCheckBox().apply { isOpaque = true }
                override fun getTableCellRendererComponent(
                    t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int,
                ): Component {
                    checkbox.isSelected = value as? Boolean ?: false
                    checkbox.background = t.background
                    checkbox.border = null
                    return checkbox
                }
            }
        }
        columnModel.getColumn(1).apply { preferredWidth = 120 }
        columnModel.getColumn(2).apply { preferredWidth = 100 }
        columnModel.getColumn(3).apply { preferredWidth = 58; maxWidth = 68 }
        columnModel.getColumn(4).apply { minWidth = 70; maxWidth = 70; preferredWidth = 70 }
        columnModel.getColumn(5).apply { preferredWidth = 130 }
        columnModel.getColumn(6).apply { preferredWidth = 130 }

        columnModel.getColumn(1).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int,
            ): Component {
                val c = super.getTableCellRendererComponent(t, value, sel, false, row, col) as JLabel
                c.border = javax.swing.BorderFactory.createEmptyBorder(0, 6, 0, 0)
                val agent = rows[row].agent
                c.icon = FaviconLoader.get(agent)
                c.iconTextGap = 8
                return c
            }
        }

        columnModel.getColumn(3).cellRenderer = object : DefaultTableCellRenderer() {
            private val installedColor = JBColor(Color(0, 128, 0), Color(98, 198, 98))
            private val outdatedColor = JBColor(Color(180, 100, 0), Color(220, 160, 60))
            private val notInstalledColor = JBColor.GRAY

            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int,
            ): Component {
                val c = super.getTableCellRendererComponent(t, value, sel, false, row, col) as JLabel
                c.border = null
                c.horizontalAlignment = CENTER
                c.foreground = when (value?.toString()) {
                    "✓" -> installedColor
                    "↑" -> outdatedColor
                    "✗" -> notInstalledColor
                    else -> t.foreground
                }
                return c
            }
        }

        // Button-styled JLabel avoids JButton look-and-feel hover side-effects.
        val actionNormalBorder = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(actionNormalBorderColor),
            javax.swing.BorderFactory.createEmptyBorder(1, 6, 1, 6),
        )
        val actionHoverBorder = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(actionHoverBorderColor),
            javax.swing.BorderFactory.createEmptyBorder(1, 6, 1, 6),
        )
        columnModel.getColumn(4).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int,
            ): Component {
                if (rows[row].agent.id in inProgressAgentIds) {
                    return JLabel(spinnerFrames[spinnerFrame]).apply {
                        isOpaque = true
                        horizontalAlignment = JLabel.CENTER
                        border = actionNormalBorder
                        background = actionNormalBg
                    }
                }
                val text = value?.toString().orEmpty()
                if (text.isBlank()) return JPanel().apply { isOpaque = false }
                val hover = row == this@AgentSettingsConfigurable.buttonHoverRow
                return JLabel(text).apply {
                    isOpaque = true
                    horizontalAlignment = JLabel.CENTER
                    border = if (hover) actionHoverBorder else actionNormalBorder
                    background = if (hover) actionHoverBg else actionNormalBg
                    if (hover && actionHoverFg != null) foreground = actionHoverFg
                }
            }
        }

        val linkRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int,
            ): Component {
                val c = super.getTableCellRendererComponent(t, value, sel, false, row, col) as JLabel
                // Extra left padding on the Website column so its text doesn't crowd the Action button beside it
                c.border = if (col == 5) javax.swing.BorderFactory.createEmptyBorder(0, 8, 0, 0) else null
                val text = value?.toString().orEmpty()
                if (text.isNotBlank()) {
                    c.foreground = JBColor.BLUE
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

        cursor = Cursor(Cursor.DEFAULT_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val col = columnAtPoint(e.point)
                val row = rowAtPoint(e.point)
                if (row < 0) return
                val agent = rows[row].agent
                when (col) {
                    1 -> { rows[row].enabled = !rows[row].enabled; tableModel.fireTableCellUpdated(row, 0) }
                    4 -> handleActionClick(agent)
                    5 -> if (isOverLinkText(e, row, col)) agent.url.takeIf { it.isNotBlank() }?.let { BrowserUtil.browse(it) }
                    6 -> if (isOverLinkText(e, row, col)) agent.devUrl.takeIf { it.isNotBlank() }?.let { BrowserUtil.browse(it) }
                }
            }
            override fun mouseExited(e: MouseEvent) {
                val oldLinkRow = linkHoverRow; val oldLinkCol = linkHoverCol
                linkHoverRow = -1; linkHoverCol = -1
                if (oldLinkRow >= 0) repaint(getCellRect(oldLinkRow, oldLinkCol, false))
                buttonHoverRow = -1
                revalidate()
                repaint()
            }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val col = columnAtPoint(e.point)
                val row = rowAtPoint(e.point)
                val actionActive = col == 4 && row >= 0 &&
                    tableModel.getValueAt(row, 4).toString().isNotBlank() &&
                    rows[row].agent.id !in inProgressAgentIds
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
                if (newButtonHover != buttonHoverRow) {
                    buttonHoverRow = newButtonHover
                    revalidate()
                    repaint()
                }
            }
        })
    }

    private val detectButton = JButton("Detect installed agents")
    private val detectStatusLabel = JBLabel("")

    private val runInBackgroundCheckbox = JBCheckBox("Run operations in background (no terminal window)")

    private val customEnabledCheckbox = JBCheckBox("Enable custom agent")
    private val customNameField = JBTextField().apply { emptyText.text = "e.g. My Agent" }
    private val customCommandField = JBTextField().apply { emptyText.text = "e.g. myagent" }
    private val customUrlField = JBTextField().apply { emptyText.text = "e.g. https://example.com" }

    private val panel: JComponent by lazy {
        detectButton.addActionListener { runAutoDetect() }
        refreshDetectStatusLabel()

        JPanel(BorderLayout()).apply {
            val content = JPanel()
            content.layout = BoxLayout(content, BoxLayout.Y_AXIS)

            content.add(titledSeparator("AgentHub"))
            content.add(Box.createVerticalStrut(4))
            content.add(
                JBLabel("Select which coding agents appear in the toolbar dropdown.").apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
            content.add(Box.createVerticalStrut(8))
            content.add(JBScrollPane(table).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                preferredSize = java.awt.Dimension(730, table.rowHeight * 15 + table.tableHeader.preferredSize.height)
            })

            content.add(Box.createVerticalStrut(16))
            content.add(titledSeparator("Custom Agent"))
            content.add(Box.createVerticalStrut(4))

            customEnabledCheckbox.alignmentX = Component.LEFT_ALIGNMENT
            content.add(customEnabledCheckbox)
            content.add(Box.createVerticalStrut(4))

            // Fixed label width so all 3 input fields start at the same x position.
            val labelWidth = listOf("Name:", "Command:", "URL:").maxOf { JBLabel(it).preferredSize.width }

            val nameRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(fixedWidthLabel("Name:", labelWidth))
                add(Box.createHorizontalStrut(4))
                add(customNameField)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            content.add(nameRow)
            content.add(Box.createVerticalStrut(4))

            val commandRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(fixedWidthLabel("Command:", labelWidth))
                add(Box.createHorizontalStrut(4))
                add(customCommandField)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            content.add(commandRow)
            content.add(Box.createVerticalStrut(4))

            val urlRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(fixedWidthLabel("URL:", labelWidth))
                add(Box.createHorizontalStrut(4))
                add(customUrlField)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            content.add(urlRow)

            content.add(Box.createVerticalStrut(16))
            content.add(titledSeparator("Behavior"))
            content.add(Box.createVerticalStrut(4))

            val buttonRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(detectButton)
                add(Box.createHorizontalStrut(12))
                add(detectStatusLabel)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            content.add(buttonRow)
            content.add(Box.createVerticalStrut(6))

            runInBackgroundCheckbox.alignmentX = Component.LEFT_ALIGNMENT
            content.add(runInBackgroundCheckbox)

            add(content, BorderLayout.NORTH)
        }
    }

    private fun titledSeparator(title: String): TitledSeparator =
        TitledSeparator(title).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun fixedWidthLabel(text: String, width: Int): JBLabel =
        JBLabel(text).apply {
            val size = java.awt.Dimension(width, preferredSize.height)
            preferredSize = size
            minimumSize = size
            maximumSize = size
        }

    private fun handleActionClick(agent: CodingAgent) {
        if (agent.id in inProgressAgentIds) return
        val isInstalled = detectedInstalled[agent.id] ?: return
        val isOutdated = agent.id in outdatedAgents
        val project = ProjectManager.getInstance().openProjects.lastOrNull() ?: return

        val isUpdate = isInstalled && isOutdated && agent.updateHint.isNotBlank()
        val (label, command, expectInstalled) = when {
            isUpdate ->
                Triple("⬆ Update ${agent.name}", agent.updateHint, true)
            isInstalled && agent.platformUninstallHint.isNotBlank() ->
                Triple("🗑 Remove ${agent.name}", agent.platformUninstallHint, false)
            !isInstalled && agent.platformInstallHint.isNotBlank() ->
                Triple("📦 Install ${agent.name}", agent.platformInstallHint, true)
            else -> return
        }

        val background = runInBackgroundCheckbox.isSelected
        if (!TerminalCommandRunner.confirmRun(project, label, command, background = background)) return

        if (background) {
            setInProgress(agent.id, true)
            runAgentCommand(project, label, command, agent, isUpdate, expectInstalled, background) {
                setInProgress(agent.id, false)
            }
        } else {
            // Terminal tool window cannot open while a modal dialog is showing — close first,
            // then re-open the Settings panel once the operation finishes.
            val dialog = DialogWrapper.findInstance(table)
            if (dialog != null) {
                apply()
                dialog.close(DialogWrapper.OK_EXIT_CODE)
                ApplicationManager.getApplication().invokeLater {
                    runAgentCommand(project, label, command, agent, isUpdate, expectInstalled, background) {
                        reopenSettings(project)
                    }
                }
            } else {
                runAgentCommand(project, label, command, agent, isUpdate, expectInstalled, background) {
                    reopenSettings(project)
                }
            }
        }
    }

    private fun reopenSettings(project: Project) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "AgentHub")
    }

    private fun runAgentCommand(
        project: Project,
        label: String,
        command: String,
        agent: CodingAgent,
        isUpdate: Boolean,
        expectInstalled: Boolean,
        background: Boolean,
        onComplete: () -> Unit,
    ) {
        if (background) {
            TerminalCommandRunner.runInBackground(project, label, command)
        } else {
            TerminalCommandRunner.run(project, label, command)
        }
        if (isUpdate) {
            AgentSettingsState.getInstance().removeOutdatedAgent(agent.id)
            DetectionResultsWatcher.watchCommandAvailability(project, agent, expectInstalled, isUpdate = true, onComplete = onComplete)
        } else {
            DetectionResultsWatcher.watchCommandAvailability(project, agent, expectInstalled, onComplete = onComplete)
        }
    }

    // EDT-only. Toggles the per-agent spinner and starts/stops the shared repaint timer.
    private fun setInProgress(id: String, active: Boolean) {
        if (active) inProgressAgentIds.add(id) else inProgressAgentIds.remove(id)
        if (inProgressAgentIds.isEmpty()) {
            spinnerTimer.stop()
        } else if (!spinnerTimer.isRunning) {
            spinnerTimer.start()
        }
        table.repaint()
    }

    private fun runAutoDetect() {
        detectButton.isEnabled = false
        detectButton.text = "Detecting…"
        detectStatusLabel.text = ""
        AgentDetector.detectAndNotify(ProjectManager.getInstance().openProjects.lastOrNull()) {
            detectButton.isEnabled = true
            detectButton.text = "Detect installed agents"
        }
    }

    private fun refreshDetectStatusLabel() {
        if (detectedInstalled.isEmpty()) {
            detectStatusLabel.text = ""
        } else {
            val count = detectedInstalled.values.count { it }
            val timestamp = AgentSettingsState.getInstance().getDetectionTimestamp()
            val timeStr = if (timestamp > 0L) {
                val formatted = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(timestamp))
                " · Last checked: $formatted"
            } else ""
            detectStatusLabel.text = "$count / ${detectedInstalled.size} agents installed$timeStr"
        }
    }

    private fun extractDomain(url: String): String = if (url.isBlank()) "" else try {
        URI(url).host?.removePrefix("www.") ?: url
    } catch (_: Exception) {
        url
    }

    override fun createComponent(): JComponent {
        refreshCallback = {
            outdatedAgents = AgentSettingsState.getInstance().getOutdatedAgentIds()
            detectedInstalled = AgentSettingsState.getInstance().getDetectionResults() ?: emptyMap()
            tableModel.fireTableDataChanged()
            refreshDetectStatusLabel()
        }
        reset()
        return panel
    }

    override fun disposeUIResources() {
        spinnerTimer.stop()
        refreshCallback = null
    }

    override fun isModified(): Boolean {
        val settings = AgentSettingsState.getInstance()
        val builtInModified = rows.any { it.enabled != settings.isAgentActive(it.agent.id) }
        val state = settings.getState()
        val customModified = customEnabledCheckbox.isSelected != state.customAgentEnabled ||
            customNameField.text != state.customAgentName ||
            customCommandField.text != state.customAgentCommand ||
            customUrlField.text != state.customAgentUrl
        val behaviorModified = runInBackgroundCheckbox.isSelected != state.runInBackground
        return builtInModified || customModified || behaviorModified
    }

    override fun apply() {
        val settings = AgentSettingsState.getInstance()
        rows.forEach { settings.setAgentActive(it.agent.id, it.enabled) }
        val state = settings.getState()
        state.customAgentEnabled = customEnabledCheckbox.isSelected
        state.customAgentName = customNameField.text
        state.customAgentCommand = customCommandField.text
        state.customAgentUrl = customUrlField.text
        state.runInBackground = runInBackgroundCheckbox.isSelected
    }

    override fun reset() {
        val settings = AgentSettingsState.getInstance()
        rows.forEach { it.enabled = settings.isAgentActive(it.agent.id) }
        detectedInstalled = settings.getDetectionResults() ?: emptyMap()
        outdatedAgents = settings.getOutdatedAgentIds()
        tableModel.fireTableDataChanged()
        val state = settings.getState()
        customEnabledCheckbox.isSelected = state.customAgentEnabled
        customNameField.text = state.customAgentName
        customCommandField.text = state.customAgentCommand
        customUrlField.text = state.customAgentUrl
        runInBackgroundCheckbox.isSelected = state.runInBackground
    }

    override fun getDisplayName(): String = "AgentHub"

    companion object {
        // EDT-only: set when panel is open, cleared when disposed
        private var refreshCallback: (() -> Unit)? = null

        /** Refresh the settings panel if it is currently open. Must be called on EDT. */
        fun scheduleRefresh() {
            refreshCallback?.invoke()
        }
    }
}