package com.shutterstar.agenthub.environment.skills.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.environment.skills.sync.model.ConflictResolution
import com.shutterstar.agenthub.environment.skills.sync.model.SkillDirectoryName
import com.shutterstar.agenthub.projects.ui.AgentHubUiComponents
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.nio.file.Path
import javax.swing.Box
import javax.swing.ButtonGroup
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTextField

/** One version of a skill: the shared source ([agentId] == null) or one agent's copy. */
internal data class VersionSide(val agentId: String?, val path: Path, val system: Boolean = false) {
    val shared: Boolean get() = agentId == null
}

/**
 * What the version dialog compares: the skill the user has open ([current], always shown on the
 * left) against one of [others] - the shared source when the open skill is an agent copy that has
 * one, otherwise the other copies (a shared skill is compared with its differing agent copies).
 */
internal data class VersionSides(
    val current: VersionSide,
    val others: List<VersionSide>,
    val preferredAgentId: String? = null,
    /** True when the skill already has a shared source, so two agent copies cannot be promoted over it. */
    val sharedExists: Boolean = false,
)

/** What the user decided; a null choice from the dialog means "just looked" or cancelled. */
internal data class VersionChoice(
    /** Make this copy the shared skill (neither side was shared yet). */
    val promote: VersionSide? = null,
    /** Resolve the agent copy [agentId] against the shared source. */
    val resolution: ConflictResolution? = null,
    val agentId: String? = null,
    val newDirectoryName: String? = null,
    /** Overwrite [replaceTarget]'s copy with the content of [replaceSource] (two differing agent copies). */
    val replaceSource: VersionSide? = null,
    val replaceTarget: VersionSide? = null,
)

/**
 * The one skill comparison dialog: the opened skill against one other version (a drop-down appears
 * only when there is more than one to pick), with the real diff, and - depending on what is being
 * compared - what can be done about it:
 *  - one side is the shared source: keep the shared version, keep the agent's, or keep both;
 *  - two agent copies (shared skill or not): make either the shared skill (replacing the shared one
 *    when it exists), or overwrite either copy with the other's content - vendor-provided copies
 *    are never changed;
 *  - nothing can be done (only vendor-provided copies): a read-only view with Close.
 * It only returns the choice; [SkillMutationController] routes it through the usual
 * prepare/preview/confirm pipeline, so nothing here touches the filesystem.
 */
internal class SkillVersionDialog(
    private val project: Project,
    skillTitle: String,
    private val sides: VersionSides,
    private val displayName: (String) -> String,
    private val reviewPlan: Boolean = true,
) : DialogWrapper(project, true) {
    private val otherChoice = JComboBox(sides.others.toTypedArray())
    private val actionsPanel = AgentHubUiComponents.verticalBoxPanel().apply { border = JBUI.Borders.empty() }
    private var diffPanel: ConflictDiffPanel? = null
    private var compareOk = false

    private var keepShared: JRadioButton? = null
    private var keepAgent: JRadioButton? = null
    private var keepBoth: JRadioButton? = null
    /** The radio buttons of the two-agent-copies mode, each with the choice it stands for. */
    private var pairChoices: List<Pair<JRadioButton, VersionChoice>> = emptyList()
    private val newNameField = JTextField()
    private var readOnly = false

    var result: VersionChoice? = null
        private set

    private val selectedOther: VersionSide get() = otherChoice.selectedItem as VersionSide

    /** The agent copy when exactly one side is the shared source, else null. */
    private val agentSide: VersionSide?
        get() = when {
            sides.current.shared -> selectedOther
            selectedOther.shared -> sides.current
            else -> null
        }

    init {
        title = "Compare Versions — $skillTitle"
        setOKButtonText(if (reviewPlan) "Review Plan" else "Apply")
        otherChoice.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component =
                super.getListCellRendererComponent(list, (value as? VersionSide)?.let(::label), index, selected, focus)
        }
        sides.others.firstOrNull { it.agentId == sides.preferredAgentId && it.agentId != null }?.let { otherChoice.selectedItem = it }
        otherChoice.addActionListener { refresh() }
        newNameField.isEnabled = false
        init()
    }

    private fun label(side: VersionSide): String = side.agentId?.let(displayName) ?: "Shared (canonical)"

    private fun refresh() {
        val other = selectedOther
        rebuildActions()
        diffPanel?.compare("${label(sides.current)} (opened)", sides.current.path, label(other), other.path)
    }

    private fun updateOkEnabled() {
        isOKActionEnabled = compareOk || readOnly
    }

    private fun rebuildActions() {
        actionsPanel.removeAll()
        keepShared = null
        keepAgent = null
        keepBoth = null
        pairChoices = emptyList()
        val agent = agentSide
        if (agent != null) {
            readOnly = false
            val name = label(agent)
            val shared = JRadioButton("Keep the shared version (overwrite $name's copy)")
            val target = JRadioButton("Keep $name's version (overwrite the shared copy)")
            val both = JRadioButton("Keep both ($name's version copied under a new name)")
            ButtonGroup().apply { add(shared); add(target); add(both) }
            shared.isSelected = true
            newNameField.isEnabled = false
            both.addItemListener { newNameField.isEnabled = both.isSelected }
            keepShared = shared
            keepAgent = target
            keepBoth = both
            actionsPanel.add(AgentHubUiComponents.sectionHeader("Resolution"))
            listOf(shared, target, both).forEach { actionsPanel.add(it.apply { alignmentX = JComponent.LEFT_ALIGNMENT }) }
            actionsPanel.add(
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(AgentHubUiComponents.SMALL_GAP), 0)).apply {
                    alignmentX = JComponent.LEFT_ALIGNMENT
                    add(JBLabel("New name:"))
                    add(newNameField.apply { columns = 20 })
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                },
            )
        } else {
            val options = pairOptions(sides.current, selectedOther)
            readOnly = options.none { it.enabled }
            if (readOnly) {
                actionsPanel.add(
                    hint("These copies are vendor-provided or synced by their agent, so neither can be replaced or become the shared skill. Nothing is changed."),
                )
            } else {
                val radios = options.map { option ->
                    JRadioButton(option.text + if (option.enabled) "" else " (vendor-provided, can't be changed)").apply { isEnabled = option.enabled } to option.choice
                }
                ButtonGroup().apply { radios.forEach { add(it.first) } }
                radios.first { it.first.isEnabled }.first.isSelected = true
                val warning = systemWarning()
                fun updateWarning() {
                    val system = radios.firstOrNull { it.first.isSelected }?.second?.let(::sharedSourceOf)?.takeIf { it.system }
                    warning.isVisible = system != null
                    system?.agentId?.let { warning.text = systemSkillWarningHtml(displayName(it), JBUI.scale(DIALOG_ROW_WIDTH + 200)) }
                }
                radios.forEach { it.first.addItemListener { updateWarning() } }
                pairChoices = radios
                actionsPanel.add(AgentHubUiComponents.sectionHeader("Resolution"))
                radios.forEach { actionsPanel.add(it.first.apply { alignmentX = JComponent.LEFT_ALIGNMENT }) }
                actionsPanel.add(warning)
                updateWarning()
                actionsPanel.add(Box.createVerticalStrut(AgentHubUiComponents.SMALL_GAP))
                actionsPanel.add(hint("A replaced copy is backed up first." + if (reviewPlan) " You review the plan before anything changes." else ""))
            }
        }
        setOKButtonText(if (readOnly) "Close" else if (reviewPlan) "Review Plan" else "Apply")
        updateOkEnabled()
        actionsPanel.revalidate()
        actionsPanel.repaint()
    }

    /** The copy that would become the shared skill under [choice], or null when the choice doesn't promote one. */
    private fun sharedSourceOf(choice: VersionChoice): VersionSide? = when {
        choice.promote != null -> choice.promote
        choice.resolution == ConflictResolution.KEEP_TARGET ->
            listOf(sides.current, selectedOther).firstOrNull { it.agentId == choice.agentId }
        else -> null
    }

    private fun systemWarning() = JBLabel().apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        foreground = SYSTEM_WARNING_COLOR
        isVisible = false
    }

    private class PairOption(val text: String, val enabled: Boolean, val choice: VersionChoice)

    /**
     * What can be done about two differing agent copies. Making a copy the shared skill replaces the
     * shared version when there is one; replacing overwrites the other agent's copy (never a
     * vendor-provided one, and never the shared source).
     */
    private fun pairOptions(current: VersionSide, other: VersionSide): List<PairOption> {
        val a = label(current)
        val b = label(other)
        return buildList {
            if (sides.sharedExists) {
                add(PairOption("Make $a's version the shared skill (replaces the shared version)", true, VersionChoice(resolution = ConflictResolution.KEEP_TARGET, agentId = current.agentId)))
                add(PairOption("Make $b's version the shared skill (replaces the shared version)", true, VersionChoice(resolution = ConflictResolution.KEEP_TARGET, agentId = other.agentId)))
            } else {
                add(PairOption("Use $a's version as the shared skill", true, VersionChoice(promote = current)))
                add(PairOption("Use $b's version as the shared skill", true, VersionChoice(promote = other)))
            }
            add(PairOption("Replace $b's copy with $a's version", !other.system, VersionChoice(replaceSource = current, replaceTarget = other)))
            add(PairOption("Replace $a's copy with $b's version", !current.system, VersionChoice(replaceSource = other, replaceTarget = current)))
        }
    }

    private fun hint(text: String) = JBLabel("<html><body style='width: ${JBUI.scale(DIALOG_ROW_WIDTH + 200)}px'>$text</body></html>").apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        foreground = JBColor.GRAY
    }

    override fun createCenterPanel(): JComponent {
        val other = selectedOther
        val diff = ConflictDiffPanel(
            project,
            disposable,
            "${label(sides.current)} (opened)",
            label(other),
            sides.current.path,
            other.path,
            onCompared = { ok ->
                compareOk = ok
                updateOkEnabled()
            },
        )
        diffPanel = diff
        val top = JPanel(BorderLayout(0, JBUI.scale(AgentHubUiComponents.SMALL_GAP))).apply {
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(AgentHubUiComponents.CONTROL_GAP), 0)).apply {
                    add(JBLabel("Opened:"))
                    add(JBLabel(label(sides.current)).apply { font = font.deriveFont(Font.BOLD) })
                    if (sides.others.size > 1) {
                        add(JBLabel("Compare with:"))
                        add(otherChoice)
                    } else {
                        add(JBLabel("Compared with:"))
                        add(JBLabel(label(other)).apply { font = font.deriveFont(Font.BOLD) })
                    }
                },
                BorderLayout.CENTER,
            )
        }
        val panel = JPanel(BorderLayout(0, JBUI.scale(AgentHubUiComponents.CONTROL_GAP * 2))).apply {
            border = JBUI.Borders.empty(AgentHubUiComponents.PANEL_INSET)
            add(top, BorderLayout.NORTH)
            add(diff, BorderLayout.CENTER)
            add(actionsPanel, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(860), JBUI.scale(640))
        }
        refresh()
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        val both = keepBoth ?: return null
        val agent = agentSide ?: return null
        if (!both.isSelected) return null
        val resolved = SkillDirectoryName.resolveSibling(agent.path, newNameField.text)
            ?: return ValidationInfo("Enter one directory name without path separators.", newNameField)
        if (resolved == agent.path.toAbsolutePath().normalize()) {
            return ValidationInfo("The new name must differ from the existing skill name.", newNameField)
        }
        return null
    }

    override fun doOKAction() {
        val agent = agentSide
        result = when {
            readOnly -> null
            agent != null -> VersionChoice(
                resolution = when {
                    keepAgent?.isSelected == true -> ConflictResolution.KEEP_TARGET
                    keepBoth?.isSelected == true -> ConflictResolution.KEEP_BOTH
                    else -> ConflictResolution.KEEP_CANONICAL
                },
                agentId = agent.agentId,
                newDirectoryName = newNameField.text.trim().ifBlank { null }.takeIf { keepBoth?.isSelected == true },
            )
            else -> pairChoices.firstOrNull { it.first.isSelected }?.second
        }
        super.doOKAction()
    }
}

/** Widest a wrapping row asks for inside this dialog, so it wraps instead of widening it. */
private const val DIALOG_ROW_WIDTH = 460

/** Amber/yellow, readable on both light and dark themes: the "this is a vendor-provided skill" notice. */
internal val SYSTEM_WARNING_COLOR = JBColor(java.awt.Color(0x9A6700), java.awt.Color(0xE5B143))

internal fun systemSkillWarningHtml(agentName: String, width: Int): String =
    "<html><body style='width: ${width}px'><b>Vendor-provided skill.</b> This copy ships with, or is synced by, $agentName. " +
        "Sharing it replaces $agentName's copy with a link to the shared one (backed up first), and $agentName may " +
        "restore or overwrite it on its next update.</body></html>"
