package com.shutterstar.agenthub.environment.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.environment.discovery.AgentEnvironmentDiscoveryService
import com.shutterstar.agenthub.environment.model.AgentEnvironment
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.ui.AgentHubUiComponents
import com.shutterstar.agenthub.projects.ui.LeftAlignedTabbedPane
import com.shutterstar.agenthub.projects.ui.ListHoverTracker
import com.shutterstar.agenthub.projects.ui.RoundedSelectionPanel
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.util.concurrent.atomic.AtomicLong
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

class AgentEnvironmentPanel(
    private val currentProject: Project,
    private val discoveryService: AgentEnvironmentDiscoveryService,
) : JPanel(BorderLayout()) {
    private val summaryLabel = JBLabel("Select an agent to inspect its environment")
    private val skillModel = DefaultListModel<EnvironmentRow>()
    private val mcpModel = DefaultListModel<EnvironmentRow>()
    private val instructionModel = DefaultListModel<EnvironmentRow>()
    private val warningModel = DefaultListModel<EnvironmentRow>()
    private val skillList = createList(skillModel, "No skills discovered")
    private val mcpList = createList(mcpModel, "No MCP servers discovered")
    private val instructionList = createList(instructionModel, "No instruction sources discovered")
    private val warningList = createList(warningModel, "No discovery warnings")
    private val skillPage = AgentHubUiComponents.alignedBorderlessScrollPane(skillList)
    private val mcpPage = AgentHubUiComponents.alignedBorderlessScrollPane(mcpList)
    private val instructionPage = AgentHubUiComponents.alignedBorderlessScrollPane(instructionList)
    private val warningPage = AgentHubUiComponents.alignedBorderlessScrollPane(warningList)
    private val tabs = LeftAlignedTabbedPane()
    private val requestSequence = AtomicLong()
    private var lastAgentId: String? = null
    private var lastProjects: List<DiscoveredProject>? = null

    init {
        border = JBUI.Borders.empty(AgentHubUiComponents.PANEL_INSET, 0, 0, 0)
        summaryLabel.border = JBUI.Borders.empty(
            0,
            AgentHubUiComponents.TEXT_LEFT_INSET,
            AgentHubUiComponents.CONTROL_GAP,
            0,
        )
        summaryLabel.font = summaryLabel.font.deriveFont(Font.BOLD)
        add(summaryLabel, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
    }

    fun setAgent(
        agentId: String?,
        projects: List<DiscoveredProject>,
    ) {
        if (agentId == lastAgentId && projects === lastProjects) return
        lastAgentId = agentId
        lastProjects = projects
        val requestId = requestSequence.incrementAndGet()
        clearModels()
        refreshTabs()
        if (agentId == null) {
            summaryLabel.text = "Select an agent to inspect its environment"
            setEmptyText("Select an agent")
            return
        }

        summaryLabel.text = "Discovering environment…"
        setEmptyText("Discovering…")
        AppExecutorUtil.getAppExecutorService().submit {
            val result = runCatching { discoveryService.discover(agentId, projects) }
            ApplicationManager.getApplication().invokeLater {
                if (currentProject.isDisposed || requestSequence.get() != requestId) return@invokeLater
                result.fold(
                    onSuccess = { environment -> render(agentId, environment) },
                    onFailure = { error ->
                        val text = "Environment discovery failed: ${error.javaClass.simpleName}"
                        summaryLabel.text = text
                        setEmptyText(text)
                        refreshTabs()
                    },
                )
            }
        }
    }

    private fun render(
        agentId: String,
        environment: AgentEnvironment,
    ) {
        clearModels()
        val summary = EnvironmentUiModel.agentSummary(environment)
        summaryLabel.text =
            "Global Skills ${summary.globalSkillCount} · Project Skills ${summary.projectSkillCount} · " +
            "MCP ${summary.mcpServerCount} · Instructions ${summary.instructionCount} · " +
            "Conflicts ${summary.skillConflictCount + summary.mcpConflictCount}"
        EnvironmentUiModel.skillRows(environment, agentId).forEach(skillModel::addElement)
        EnvironmentUiModel.mcpRows(environment, agentId).forEach(mcpModel::addElement)
        EnvironmentUiModel.instructionRows(environment).forEach(instructionModel::addElement)
        EnvironmentUiModel.warningRows(environment, AgentHubUiComponents::displayName).forEach(warningModel::addElement)
        setEmptyText(null)
        refreshTabs()
    }

    private fun clearModels() {
        skillModel.removeAllElements()
        mcpModel.removeAllElements()
        instructionModel.removeAllElements()
        warningModel.removeAllElements()
    }

    private fun setEmptyText(text: String?) {
        skillList.emptyText.text = text ?: "No skills discovered"
        mcpList.emptyText.text = text ?: "No MCP servers discovered"
        instructionList.emptyText.text = text ?: "No instruction sources discovered"
        warningList.emptyText.text = text ?: "No discovery warnings"
    }

    private fun refreshTabs() {
        tabs.setTabs(
            buildList {
                if (!skillModel.isEmpty) add("Skills" to skillPage)
                if (!mcpModel.isEmpty) add("MCP" to mcpPage)
                if (!instructionModel.isEmpty) add("Instructions" to instructionPage)
                if (!warningModel.isEmpty) add("Warnings" to warningPage)
            },
        )
    }

    private fun createList(
        model: DefaultListModel<EnvironmentRow>,
        emptyText: String,
    ): JBList<EnvironmentRow> = JBList(model).apply {
        cellRenderer = EnvironmentRowRenderer(ListHoverTracker(this))
        this.emptyText.text = emptyText
    }

    private class EnvironmentRowRenderer(
        private val hover: ListHoverTracker,
    ) : ListCellRenderer<EnvironmentRow> {
        private val titleLabel = JBLabel()
        private val detailLabel = JBLabel()
        private val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = AgentHubUiComponents.listRowBorder()
            titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
            detailLabel.foreground = JBColor.GRAY
            add(titleLabel)
            add(detailLabel)
        }
        private val wrapper = RoundedSelectionPanel.wrap(content).apply {
            selectionArc = AgentHubUiComponents.SELECTION_ARC
            selectionInsets = AgentHubUiComponents.listSelectionInsets()
        }

        override fun getListCellRendererComponent(
            list: JList<out EnvironmentRow>,
            value: EnvironmentRow,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            titleLabel.text = value.title
            detailLabel.text = value.detail
            wrapper.background = list.background
            val colors = AgentHubUiComponents.rowTextColors(list.foreground, isSelected)
            wrapper.selectionColor = AgentHubUiComponents.rowHighlight(isSelected, hover.isHovered(index))
            titleLabel.foreground = colors.foreground
            detailLabel.foreground = colors.secondaryForeground
            return wrapper
        }
    }

}
