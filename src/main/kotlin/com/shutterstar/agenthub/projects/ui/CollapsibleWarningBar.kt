package com.shutterstar.agenthub.projects.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * A one-line "N discovery warnings" toggle that expands into the warning texts. Shared by the
 * Environment and Skills views: warnings are context, not content, so they stay out of the way
 * until asked for. The expanded/collapsed choice is the user's and deliberately outlives
 * [setWarnings] calls — switching entity or refreshing does not fold it back; the bar simply
 * disappears while there is nothing to warn about.
 */
internal class CollapsibleWarningBar(private val maxShown: Int = MAX_SHOWN) : JPanel(BorderLayout()) {
    private var expanded = false
    private var warnings: List<String> = emptyList()
    private val toggle = ActionLink("") { setExpanded(!expanded) }
    private val list = object : JPanel() {
        override fun doLayout() {
            (layout as? BoxLayout)?.invalidateLayout(this)
            super.doLayout()
        }
    }.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.emptyLeft(ICON_INDENT)
    }

    /** The current toggle caption, e.g. "2 discovery warnings" (empty while there is nothing to show). */
    val toggleText: String get() = toggle.text.orEmpty()

    val isExpanded: Boolean get() = expanded

    init {
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, AgentHubUiComponents.TEXT_LEFT_INSET, AgentHubUiComponents.CONTROL_GAP, 0)
        add(toggle, BorderLayout.NORTH)
        add(list, BorderLayout.CENTER)
        isVisible = false
        applyExpanded()
    }

    fun setExpanded(value: Boolean) {
        expanded = value
        applyExpanded()
    }

    fun setWarnings(warnings: List<String>) {
        this.warnings = warnings.toList()
        list.removeAll()
        warnings.take(maxShown).forEach { warning ->
            list.add(AgentHubUiComponents.wrappingStatusText().apply {
                text = warning
                isFocusable = true
                accessibleContext.accessibleName = warning
                putClientProperty("html.disable", true)
                toolTipText = warning
            })
        }
        if (warnings.isNotEmpty()) {
            list.add(ActionLink("Show all warnings (${warnings.size})") { showAllWarnings() })
            list.add(ActionLink("Copy diagnostics") { copyDiagnostics() })
        }
        toggle.text = when (warnings.size) {
            0 -> ""
            1 -> "1 discovery warning"
            else -> "${warnings.size} discovery warnings"
        }
        isVisible = warnings.isNotEmpty()
        applyExpanded()
    }

    internal fun diagnosticsText(): String = warnings.joinToString("\n\n")

    private fun copyDiagnostics() {
        com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(diagnosticsText()))
    }

    private fun showAllWarnings() {
        val text = javax.swing.JTextArea(diagnosticsText()).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            caretPosition = 0
            accessibleContext.accessibleName = "All discovery warnings"
        }
        object : com.intellij.openapi.ui.DialogWrapper(true) {
            init { title = "Discovery warnings (${warnings.size})"; setOKButtonText("Close"); init() }
            override fun createCenterPanel(): javax.swing.JComponent = JPanel(BorderLayout()).apply {
                add(com.intellij.ui.components.JBScrollPane(text).apply { preferredSize = JBUI.size(560, 360) }, BorderLayout.CENTER)
                add(javax.swing.JButton("Copy diagnostics").apply { addActionListener { copyDiagnostics() } }, BorderLayout.SOUTH)
            }
            override fun createActions(): Array<javax.swing.Action> = arrayOf(okAction)
            override fun getPreferredFocusedComponent(): javax.swing.JComponent = text
        }.show()
    }

    private fun applyExpanded() {
        toggle.icon = if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        list.isVisible = expanded
        revalidate()
        repaint()
    }

    private companion object {
        const val MAX_SHOWN = 20
        const val ICON_INDENT = 16
    }
}
