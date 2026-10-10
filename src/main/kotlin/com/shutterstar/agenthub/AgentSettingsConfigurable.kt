package com.shutterstar.agenthub

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.projects.ui.AgentHubUiComponents
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.DocumentEvent

class AgentSettingsConfigurable(private val settingsState: AgentSettingsState = AgentSettingsState.getInstance()) : Configurable {

    // Mutable: `available()` depends on the WSL mode, so switching Windows ↔ WSL in this very
    // panel must rebuild the row lists (see rebuildRows) — the tables keep referencing these
    // same list instances.
    private val agentRows: MutableList<AgentRow> = CodingAgents.available().map { AgentRow(it, false) }.toMutableList()
    private val companionRows: MutableList<AgentRow> = CompanionTools.available().map { AgentRow(it, false) }.toMutableList()

    // Detection results: null = not yet checked, true/false = result
    private var detectedInstalled: Map<String, Boolean> = settingsState.getDetectionResults() ?: emptyMap()
    private var outdatedAgents: Set<String> = settingsState.getOutdatedAgentIds()
    private var unverifiedAgents: Set<String> = settingsState.getUnverifiedAgentIds()

    // Agents with an install/update/remove in flight; their Action cell shows an animated spinner.
    private val inProgressAgentIds get() = activeOperationAgentIds
    private var uiDisposed = false
    private val spinnerFrames = arrayOf("◐", "◓", "◑", "◒")
    private var spinnerFrame = 0
    private val spinnerTimer = Timer(130) {
        spinnerFrame = (spinnerFrame + 1) % spinnerFrames.size
        tables.forEach { it.repaintInProgress() }
    }

    // Shared with AgentTable via AgentTableContext lambdas (below) so both the agent and companion
    // tables always render the current detection/spinner state, not a snapshot from construction time.
    private val agentTableContext = AgentTableContext(
        detectedInstalled = { detectedInstalled },
        outdatedAgents = { outdatedAgents },
        unverifiedAgents = { unverifiedAgents },
        inProgressAgentIds = { inProgressAgentIds },
        spinnerFrame = { spinnerFrame },
        spinnerFrames = spinnerFrames,
        onActionClick = ::handleActionClick,
    )
    private val agentTable = AgentTable(agentRows, agentTableContext)
    private val companionTable = AgentTable(companionRows, agentTableContext)
    private val tables = listOf(agentTable, companionTable)

    private val detectButton = JButton("Detect installed agents")
    private val checkInstalledAgentsButton = JButton("Check installed agents")
    private val checkInstalledToolsButton = JButton("Check installed tools")
    // Headings carry the environment the selection belongs to: Windows and every WSL distribution keep their own.
    private val agentsSeparator = titledSeparator("AgentHub")
    private val companionsSeparator = titledSeparator("Companion Tools")
    private val agentsIntroLabel = JBLabel("Select which coding agents appear in the toolbar dropdown.").apply {
        alignmentX = Component.LEFT_ALIGNMENT
    }
    private val customValidationLabel = JBLabel("").apply { isVisible = false }
    private val detectStatusLabel = JBLabel("")

    // Shown above the agent table until detection has run at least once, so the "blank status
    // column" state has an explicit explanation instead of relying on detectStatusLabel below the
    // table, which is easy to miss on first open.
    private val detectionBannerLabel = JBLabel("Agent status unknown — click \"Detect installed agents\" below to check what's installed.").apply {
        foreground = JBColor.GRAY
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private val agentSearchField = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search agents…"
        textEditor.accessibleContext.accessibleName = "Search agents"
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }
    private val companionSearchField = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search companion tools…"
        textEditor.accessibleContext.accessibleName = "Search companion tools"
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private val runInBackgroundCheckbox = JBCheckBox("Run operations in background (no terminal window)")

    // Execution environment (Windows only): native shell vs. a WSL distribution.
    private val execEnvCombo = ComboBox(arrayOf("Windows (native)", "WSL"))
    private val wslDistroCombo = ComboBox<String>().apply { prototypeDisplayValue = "Ubuntu-24.04-LTS-xxxx" }
    private val wslStatusLabel = JBLabel("")
    private var wslDistrosLoaded = false

    private val customEnabledCheckbox = JBCheckBox("Enable custom agent")
    private val customNameField = JBTextField().apply { emptyText.text = "e.g. My Agent" }
    private val customCommandField = JBTextField().apply { emptyText.text = "e.g. myagent" }
    private val customUrlField = JBTextField().apply { emptyText.text = "e.g. https://example.com" }

    private val panel: JComponent by lazy {
        detectButton.addActionListener { runAutoDetect() }
        refreshDetectStatusLabel()

        agentSearchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = agentTable.applyFilter(agentSearchField.text)
        })
        companionSearchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = companionTable.applyFilter(companionSearchField.text)
        })

        JPanel(BorderLayout()).apply {
            val content = JPanel()
            content.layout = BoxLayout(content, BoxLayout.Y_AXIS)

            content.add(agentsSeparator)
            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.SMALL_GAP)))
            content.add(agentsIntroLabel)
            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.SMALL_GAP)))
            content.add(detectionBannerLabel)
            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.PANEL_INSET)))
            content.add(agentSearchField)
            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))
            content.add(JBScrollPane(agentTable.table).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                preferredSize = Dimension(JBUI.scale(AgentHubUiComponents.SETTINGS_TABLE_WIDTH), agentTable.table.rowHeight * 15 + agentTable.table.tableHeader.preferredSize.height)
            })
            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))
            content.add(checkInstalledRow(checkInstalledAgentsButton, agentRows, agentTable))

            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.SETTINGS_SECTION_GAP)))
            content.add(companionsSeparator)
            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.SMALL_GAP)))
            content.add(
                JBLabel("Optional CLI utilities that work alongside the agents.").apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.PANEL_INSET)))
            content.add(companionSearchField)
            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))
            content.add(JBScrollPane(companionTable.table).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                val visibleRows = companionRows.size.coerceIn(1, 5)
                preferredSize = Dimension(JBUI.scale(AgentHubUiComponents.SETTINGS_TABLE_WIDTH), companionTable.table.rowHeight * visibleRows + companionTable.table.tableHeader.preferredSize.height)
            })
            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))
            content.add(checkInstalledRow(checkInstalledToolsButton, companionRows, companionTable))

            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.SETTINGS_SECTION_GAP)))
            content.add(titledSeparator("Custom Agent"))
            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.SMALL_GAP)))

            customEnabledCheckbox.alignmentX = Component.LEFT_ALIGNMENT
            content.add(customEnabledCheckbox)
            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.SMALL_GAP)))

            // Fixed label width so all 3 input fields start at the same x position.
            val labelWidth = listOf("Name:", "Command:", "URL:").maxOf { JBLabel(it).preferredSize.width }

            val nameRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(fixedWidthLabel("Name:", labelWidth).apply { labelFor = customNameField; customNameField.accessibleContext.accessibleName = "Name" })
                add(Box.createHorizontalStrut(JBUI.scale(AgentHubUiComponents.SMALL_GAP)))
                add(customNameField)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            content.add(nameRow)
            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.SMALL_GAP)))

            val commandRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(fixedWidthLabel("Command:", labelWidth).apply { labelFor = customCommandField; customCommandField.accessibleContext.accessibleName = "Command" })
                add(Box.createHorizontalStrut(JBUI.scale(AgentHubUiComponents.SMALL_GAP)))
                add(customCommandField)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            content.add(commandRow)
            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.SMALL_GAP)))

            val urlRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(fixedWidthLabel("URL:", labelWidth).apply { labelFor = customUrlField; customUrlField.accessibleContext.accessibleName = "URL" })
                add(Box.createHorizontalStrut(JBUI.scale(AgentHubUiComponents.SMALL_GAP)))
                add(customUrlField)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            content.add(urlRow)
            content.add(customValidationLabel)

            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.SETTINGS_SECTION_GAP)))
            content.add(titledSeparator("Behavior"))
            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.SMALL_GAP)))

            val buttonRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(detectButton)
                add(Box.createHorizontalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP * 2)))
                add(detectStatusLabel)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            content.add(buttonRow)
            content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))

            runInBackgroundCheckbox.alignmentX = Component.LEFT_ALIGNMENT
            content.add(runInBackgroundCheckbox)

            if (OsDetector.isWindows()) {
                content.add(Box.createVerticalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))
                execEnvCombo.maximumSize = execEnvCombo.preferredSize
                wslDistroCombo.maximumSize = wslDistroCombo.preferredSize
                execEnvCombo.addActionListener {
                    val wsl = execEnvCombo.selectedIndex == 1
                    wslDistroCombo.isEnabled = wsl
                    if (wsl) loadWslDistrosAsync() else wslStatusLabel.text = ""
                }
                val wslRow = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    add(JBLabel("Run agents in:").apply { labelFor = execEnvCombo })
                    add(Box.createHorizontalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))
                    add(execEnvCombo)
                    add(Box.createHorizontalStrut(JBUI.scale(AgentHubUiComponents.PANEL_INSET)))
                    wslDistroCombo.accessibleContext.accessibleName = "WSL distribution"
                    execEnvCombo.accessibleContext.accessibleName = "Agent execution environment"
                    add(wslDistroCombo)
                    add(Box.createHorizontalStrut(JBUI.scale(AgentHubUiComponents.PANEL_INSET)))
                    add(wslStatusLabel)
                    alignmentX = Component.LEFT_ALIGNMENT
                }
                content.add(wslRow)
            }

            add(content, BorderLayout.NORTH)
        }
    }

    /**
     * The row under a table: one button that ticks what the last detection found installed and unticks what it found
     * missing (agents it knows nothing about stay as they are). Edits the table only; Apply saves it like any change.
     */
    private fun checkInstalledRow(button: JButton, rows: List<AgentRow>, table: AgentTable): JComponent {
        button.addActionListener {
            rows.forEach { row -> detectedInstalled[row.agent.id]?.let { installed -> row.enabled = installed } }
            table.tableModel.fireTableDataChanged()
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(button)
            add(Box.createHorizontalStrut(JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))
            add(JBLabel("Ticks the installed ones, unticks the missing ones.").apply { foreground = JBColor.GRAY })
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    /** The buttons need a detection result to go by; the headings name the environment the tables belong to. */
    private fun updateEnvironmentUi() {
        val known = detectedInstalled.isNotEmpty()
        listOf(checkInstalledAgentsButton, checkInstalledToolsButton).forEach { button ->
            button.isEnabled = known
            button.toolTipText = if (known) {
                "Tick the agents and tools found installed by the last detection, untick the ones found missing"
            } else {
                "Run \"Detect installed agents\" first"
            }
        }
        // Only WSL mode names its environment: the native Windows setup is the default and needs no label.
        val state = settingsState.getState()
        val inWsl = OsDetector.isWindows() && state.useWsl
        val label = AgentSettingsState.runtimeLabel(true, state.wslDistro)
        agentsSeparator.text = if (inWsl) "AgentHub · $label" else "AgentHub"
        companionsSeparator.text = if (inWsl) "Companion Tools · $label" else "Companion Tools"
        agentsIntroLabel.text = if (inWsl) {
            "Select which coding agents appear in the toolbar dropdown. Windows and each WSL distribution keep their own selection."
        } else {
            "Select which coding agents appear in the toolbar dropdown."
        }
    }

    private fun titledSeparator(title: String): TitledSeparator =
        TitledSeparator(title).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun fixedWidthLabel(text: String, width: Int): JBLabel =
        JBLabel(text).apply {
            val size = Dimension(width, preferredSize.height)
            preferredSize = size
            minimumSize = size
            maximumSize = size
        }

    private fun handleActionClick(agent: CodingAgent) {
        if (agent.id in inProgressAgentIds) return
        val isInstalled = detectedInstalled[agent.id] ?: return
        val isOutdated = agent.id in outdatedAgents
        val project = ProjectManager.getInstance().openProjects.lastOrNull() ?: return

        val background = runInBackgroundCheckbox.isSelected
        val savedExecution = WslSupport.settings
        if (OsDetector.isWindows() && (uiUseWsl() != savedExecution.useWsl ||
                (uiUseWsl() && uiWslDistro() != savedExecution.distro))) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                "Apply the execution environment changes and detect agents before running Install, Update or Remove. Pending settings have not been saved.",
                "Execution environment changed",
            )
            return
        }
        val dialog = if (background) null else DialogWrapper.findInstance(agentTable.table)
        val savesSettings = dialog != null
        val executionSettings = if (savesSettings) WslSupport.Settings(uiUseWsl(), uiWslDistro()) else WslSupport.settings
        val useWsl = OsDetector.isWindows() && executionSettings.useWsl

        val isUpdate = isInstalled && isOutdated && agent.updateHint.isNotBlank()
        val (label, command, expectInstalled) = when {
            isUpdate ->
                Triple("⬆ Update ${agent.name}", agent.updateCommand(useWsl), true)
            isInstalled && agent.uninstallCommand(useWsl).isNotBlank() ->
                Triple("🗑 Remove ${agent.name}", agent.uninstallCommand(useWsl), false)
            !isInstalled && agent.installCommand(useWsl).isNotBlank() ->
                Triple("📦 Install ${agent.name}", agent.installCommand(useWsl), true)
            else -> return
        }

        if (savesSettings) {
            try {
                validateCustomCommand()
            } catch (error: ConfigurationException) {
                customValidationLabel.text = "Command: enter a command for the enabled custom agent."
            customValidationLabel.isVisible = true
            customCommandField.requestFocusInWindow()
                com.intellij.openapi.ui.Messages.showErrorDialog(project, error.localizedMessage, "AgentHub settings")
                return
            }
        }
        val context = if (savesSettings) {
            "All AgentHub settings will be saved, including agent selections, custom agent fields, background mode and WSL settings. Settings will close and reopen after the operation."
        } else {
            "This operation uses the saved execution environment. Pending settings changes will not be saved."
        }
        setInProgress(agent.id, true)
        val confirmed = try {
            TerminalCommandRunner.confirmRun(
                project, label, command, context, background, executionSettings,
                confirmText = if (savesSettings) "Save settings and run" else "Run",
            )
        } catch (error: Exception) {
            setInProgress(agent.id, false)
            throw error
        }
        if (!confirmed) {
            setInProgress(agent.id, false)
            return
        }

        if (background) {
            runAgentCommand(project, label, command, agent, isUpdate, expectInstalled, background, executionSettings) {
                setInProgress(agent.id, false)
            }
        } else {
            // Terminal tool window cannot open while a modal dialog is showing — close first,
            // then re-open the Settings panel once the operation finishes.
            if (dialog != null) {
                try {
                    apply()
                } catch (error: Exception) {
                    setInProgress(agent.id, false)
                    throw error
                }
                // The dialog vanishing without warning reads as a glitch — a quick notification
                // explains why, before the terminal takes over and Settings reopens on completion.
                DetectionResultsWatcher.showNotification(
                    project,
                    label,
                    "AgentHub settings saved. Settings closed to run this in the terminal — it will reopen when done.",
                    NotificationType.INFORMATION,
                )
                dialog.close(DialogWrapper.OK_EXIT_CODE)
                ApplicationManager.getApplication().invokeLater {
                    runAgentCommand(project, label, command, agent, isUpdate, expectInstalled, background, executionSettings) {
                        setInProgress(agent.id, false)
                        reopenSettings(project)
                    }
                }
            } else {
                runAgentCommand(project, label, command, agent, isUpdate, expectInstalled, background, executionSettings) {
                    setInProgress(agent.id, false)
                    reopenSettings(project)
                }
            }
        }
    }

    private fun reopenSettings(project: Project) {
        if (!project.isDisposed) ShowSettingsUtil.getInstance().showSettingsDialog(project, "AgentHub")
    }

    // Recomputes the visible agent/companion rows for the current execution environment
    // (the WSL setting was just applied, so available() already reflects the new mode).
    private fun rebuildRows() {
        val settings = settingsState
        agentRows.clear()
        agentRows.addAll(CodingAgents.available().map { AgentRow(it, settings.isAgentActive(it.id)) })
        companionRows.clear()
        companionRows.addAll(CompanionTools.available().map { AgentRow(it, settings.isCompanionActive(it.id)) })
        tables.forEach { it.tableModel.fireTableDataChanged() }
    }

    private fun runAgentCommand(
        project: Project,
        label: String,
        command: String,
        agent: CodingAgent,
        isUpdate: Boolean,
        expectInstalled: Boolean,
        background: Boolean,
        executionSettings: WslSupport.Settings,
        onComplete: () -> Unit,
    ) {
        val snapshot = settingsState.executionSnapshot()
        TerminalCommandRunner.runTracked(project, label, command, background, executionSettings) { exitCode ->
            if (!settingsState.isExecutionCurrent(snapshot)) {
                onComplete()
                return@runTracked
            }
            if (exitCode != 0 || project.isDisposed) {
                if (!project.isDisposed) DetectionResultsWatcher.showNotification(project, label, "Operation failed (exit code $exitCode). Run Detect to refresh the agent status.", NotificationType.ERROR)
                onComplete()
            } else {
                if (isUpdate) settingsState.removeOutdatedAgent(agent.id)
                DetectionResultsWatcher.watchCommandAvailability(project, agent, expectInstalled, isUpdate = isUpdate, commandFinished = true, snapshot = snapshot, onCancelled = onComplete, onComplete = onComplete)
            }
        }
    }

    // EDT-only. Toggles the per-agent spinner and starts/stops the shared repaint timer.
    private fun setInProgress(id: String, active: Boolean) {
        if (active) inProgressAgentIds.add(id) else inProgressAgentIds.remove(id)
        if (inProgressAgentIds.isEmpty() || uiDisposed) {
            spinnerTimer.stop()
        } else if (!spinnerTimer.isRunning) {
            spinnerTimer.start()
        }
        tables.forEach { it.table.repaint() }
        progressRefreshCallback?.invoke()
    }

    private fun uiUseWsl(): Boolean = OsDetector.isWindows() && execEnvCombo.selectedIndex == 1

    private fun uiWslDistro(): String =
        (wslDistroCombo.selectedItem as? String)?.takeIf { it != DEFAULT_DISTRO_ITEM } ?: ""

    // Before the real distro list arrives, show just the default entry plus the saved distro so
    // an early Apply cannot lose the persisted selection.
    private fun seedDistroCombo(saved: String) {
        wslDistroCombo.removeAllItems()
        wslDistroCombo.addItem(DEFAULT_DISTRO_ITEM)
        if (saved.isNotEmpty()) wslDistroCombo.addItem(saved)
        wslDistroCombo.selectedItem = if (saved.isEmpty()) DEFAULT_DISTRO_ITEM else saved
    }

    private fun selectDistro(saved: String) {
        if (!wslDistrosLoaded) {
            seedDistroCombo(saved)
            return
        }
        if (saved.isNotEmpty() && (0 until wslDistroCombo.itemCount).none { wslDistroCombo.getItemAt(it) == saved }) {
            wslDistroCombo.addItem(saved)
        }
        wslDistroCombo.selectedItem = if (saved.isEmpty()) DEFAULT_DISTRO_ITEM else saved
    }

    // `wsl.exe --list` runs off the EDT; ModalityState.any() so the result lands while the modal
    // Settings dialog is open (same pattern as AgentDetector.detectAndNotify).
    private fun loadWslDistrosAsync() {
        if (wslDistrosLoaded) return
        val selected = uiWslDistro()
        wslStatusLabel.text = "Loading distributions…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val distros = WslSupport.listDistros()
            ApplicationManager.getApplication().invokeLater({
                wslDistrosLoaded = true
                wslDistroCombo.removeAllItems()
                wslDistroCombo.addItem(DEFAULT_DISTRO_ITEM)
                distros.forEach { wslDistroCombo.addItem(it) }
                if (selected.isNotEmpty() && selected !in distros) wslDistroCombo.addItem(selected)
                wslDistroCombo.selectedItem = if (selected.isEmpty()) DEFAULT_DISTRO_ITEM else selected
                wslStatusLabel.text = if (distros.isEmpty()) "WSL not detected on this system" else ""
            }, ModalityState.any())
        }
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
        updateEnvironmentUi()
        detectionBannerLabel.isVisible = detectedInstalled.isEmpty()
        if (detectedInstalled.isEmpty()) {
            detectStatusLabel.text = ""
        } else {
            val count = detectedInstalled.values.count { it }
            val timestamp = settingsState.getDetectionTimestamp()
            val timeStr = if (timestamp > 0L) {
                val formatted = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(timestamp))
                " · Last checked: $formatted"
            } else ""
            detectStatusLabel.text = "$count / ${detectedInstalled.size} installed$timeStr"
        }
    }

    override fun createComponent(): JComponent {
        uiDisposed = false
        progressRefreshCallback = {
            if (inProgressAgentIds.isEmpty()) spinnerTimer.stop() else if (!spinnerTimer.isRunning) spinnerTimer.start()
            tables.forEach { it.table.repaint() }
        }
        refreshCallback = {
            outdatedAgents = settingsState.getOutdatedAgentIds()
            unverifiedAgents = settingsState.getUnverifiedAgentIds()
            detectedInstalled = settingsState.getDetectionResults() ?: emptyMap()
            tables.forEach { it.tableModel.fireTableDataChanged() }
            refreshDetectStatusLabel()
            progressRefreshCallback?.invoke()
        }
        reset()
        return panel
    }

    override fun disposeUIResources() {
        uiDisposed = true
        spinnerTimer.stop()
        refreshCallback = null
        progressRefreshCallback = null
    }

    override fun isModified(): Boolean {
        val settings = settingsState
        val builtInModified = agentRows.any { it.enabled != settings.isAgentActive(it.agent.id) }
        val companionModified = companionRows.any { it.enabled != settings.isCompanionActive(it.agent.id) }
        val state = settings.getState()
        val customModified = customEnabledCheckbox.isSelected != state.customAgentEnabled ||
            customNameField.text != state.customAgentName ||
            customCommandField.text != state.customAgentCommand ||
            customUrlField.text != state.customAgentUrl
        val behaviorModified = runInBackgroundCheckbox.isSelected != state.runInBackground
        val wslModified = OsDetector.isWindows() &&
            (uiUseWsl() != state.useWsl || uiWslDistro() != state.wslDistro)
        return builtInModified || companionModified || customModified || behaviorModified || wslModified
    }

    override fun apply() {
        validateCustomCommand()
        val settings = settingsState
        agentRows.forEach { settings.setAgentActive(it.agent.id, it.enabled) }
        companionRows.forEach { settings.setCompanionActive(it.agent.id, it.enabled) }
        val state = settings.getState()
        state.customAgentEnabled = customEnabledCheckbox.isSelected
        state.customAgentName = customNameField.text
        state.customAgentCommand = customCommandField.text
        state.customAgentUrl = customUrlField.text
        state.runInBackground = runInBackgroundCheckbox.isSelected

        if (OsDetector.isWindows()) {
            val newUseWsl = uiUseWsl()
            val newDistro = uiWslDistro()
            val envChanged = newUseWsl != state.useWsl || (newUseWsl && newDistro != state.wslDistro)
            settings.setWslMode(newUseWsl, newDistro)
            if (envChanged) {
                // Each environment keeps its own selection and detection results ([AgentSettingsState.setWslMode]
                // swapped them in); a fresh detection brings them up to date.
                detectedInstalled = settings.getDetectionResults() ?: emptyMap()
                outdatedAgents = settings.getOutdatedAgentIds()
                unverifiedAgents = settings.getUnverifiedAgentIds()
                // The agent list itself also differs: WSL mode surfaces the unsupportedOnWindows
                // agents (forge, leanctl, …), native mode hides them.
                rebuildRows()
                refreshDetectStatusLabel()
                AgentDetector.detectAndNotify(ProjectManager.getInstance().openProjects.lastOrNull())
            }
        }
    }

    private fun validateCustomCommand() {
        customValidationLabel.isVisible = false
        val url = customUrlField.text.trim()
        if (url.isNotEmpty()) {
            val uri = runCatching { java.net.URI(url) }.getOrNull()
            if (uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                customValidationLabel.text = "URL: enter a complete http:// or https:// address, or leave it empty."
                customValidationLabel.isVisible = true
                customUrlField.requestFocusInWindow()
                customUrlField.toolTipText = "Enter a complete http:// or https:// URL, or leave it empty."
                throw ConfigurationException("Enter a complete http:// or https:// URL, or leave it empty.")
            }
        }
        if (customEnabledCheckbox.isSelected && customCommandField.text.isBlank()) {
            customValidationLabel.text = "Command: enter a command for the enabled custom agent."
            customValidationLabel.isVisible = true
            customCommandField.requestFocusInWindow()
            customCommandField.toolTipText = "Enter a command for the enabled custom agent."
            throw ConfigurationException("Enter a command for the enabled custom agent.")
        }
    }

    override fun reset() {
        val settings = settingsState
        agentRows.forEach { it.enabled = settings.isAgentActive(it.agent.id) }
        companionRows.forEach { it.enabled = settings.isCompanionActive(it.agent.id) }
        detectedInstalled = settings.getDetectionResults() ?: emptyMap()
        outdatedAgents = settings.getOutdatedAgentIds()
        unverifiedAgents = settings.getUnverifiedAgentIds()
        tables.forEach { it.tableModel.fireTableDataChanged() }
        updateEnvironmentUi()
        if (inProgressAgentIds.isNotEmpty() && !uiDisposed && !spinnerTimer.isRunning) spinnerTimer.start()
        val state = settings.getState()
        customEnabledCheckbox.isSelected = state.customAgentEnabled
        customNameField.text = state.customAgentName
        customCommandField.text = state.customAgentCommand
        customUrlField.text = state.customAgentUrl
        runInBackgroundCheckbox.isSelected = state.runInBackground
        if (OsDetector.isWindows()) {
            execEnvCombo.selectedIndex = if (state.useWsl) 1 else 0
            selectDistro(state.wslDistro)
            wslDistroCombo.isEnabled = state.useWsl
            if (state.useWsl) loadWslDistrosAsync()
        }
    }

    override fun getDisplayName(): String = "AgentHub"

    companion object {
        // EDT-only and shared across Settings instances, including panels reopened during a command.
        private val activeOperationAgentIds = mutableSetOf<String>()
        private const val DEFAULT_DISTRO_ITEM = "Default distribution"

        // EDT-only: set when panel is open, cleared when disposed
        private var refreshCallback: (() -> Unit)? = null
        private var progressRefreshCallback: (() -> Unit)? = null

        /** Refresh the settings panel if it is currently open. Must be called on EDT. */
        fun scheduleRefresh() {
            refreshCallback?.invoke()
        }
    }
}
