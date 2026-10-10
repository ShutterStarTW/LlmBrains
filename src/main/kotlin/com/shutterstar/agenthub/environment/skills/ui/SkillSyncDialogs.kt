package com.shutterstar.agenthub.environment.skills.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAuditEntry
import com.shutterstar.agenthub.environment.skills.sync.execution.StoredBackupRecord
import com.shutterstar.agenthub.environment.skills.sync.migration.BulkMigrationCandidate
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncRequest
import com.shutterstar.agenthub.environment.skills.sync.settings.SkillSyncSettings
import com.shutterstar.agenthub.environment.skills.sync.undo.UndoPreview
import com.shutterstar.agenthub.projects.ui.AgentHubUiComponents
import com.shutterstar.agenthub.projects.ui.AgentHubUiFormat
import com.shutterstar.agenthub.storage.AgentHubStorage
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.border.Border

/**
 * Multi-select target picker shared by every "which agents" step in the Skills sync flow, so
 * there's exactly one checkbox-list implementation instead of a near-duplicate for each caller:
 * "Share with…" (starts with exactly the agents the skill is shared with; unchecking one of those
 * stops sharing with it, so OK needs a real change) and Promote's optional "also share with" step
 * (nothing checked, zero allowed - leaving everything unchecked keeps promote-only behavior).
 */
internal class SkillTargetMultiSelectDialog(
    project: Project,
    targets: List<String>,
    private val displayName: (String) -> String,
    private val agentIcon: (String) -> Icon? = { null },
    dialogTitle: String,
    okButtonText: String,
    private val initiallyChecked: Set<String>,
    private val allowEmpty: Boolean,
    private val emptyMessage: String = "No other supported installed agents are available.",
    /** One line of guidance above the list; plain text, never user content. */
    private val hint: String? = null,
    /** True when OK must stay disabled until the selection differs from [initiallyChecked]. */
    private val requireChange: Boolean = false,
    /** Agents shown checked but not changeable (e.g. shared in a way AgentHub cannot remove); still part of [selectedTargetIds]. */
    private val lockedIds: Set<String> = emptySet(),
    /** Per locked agent: why it can't change (shown after its name and as tooltip); falls back to the generic "can't remove" text. */
    private val lockedNotes: Map<String, String> = emptyMap(),
    /**
     * A collection heading and its members, listed together under that heading (e.g. the agents that
     * read the shared directory directly); the rest follow under "Other agents". Members need no
     * per-row note: the heading says why they are grouped.
     */
    private val groupTitle: String? = null,
    private val groupedIds: Set<String> = emptySet(),
    /** Amber notice above the list (e.g. the skill being promoted is vendor-provided); null for none. */
    private val warningHtml: String? = null,
) : DialogWrapper(project, true) {
    private val checkboxes = targets.map { id ->
        val locked = id in lockedIds
        id to JCheckBox("", id in initiallyChecked).apply {
            accessibleContext.accessibleName = displayName(id)
            if (locked) {
                isEnabled = false
                toolTipText = (if (id in groupedIds) "Reads the shared directory directly, so nothing needs to be created for it." else null)
                    ?: lockedNotes[id]?.replaceFirstChar(Char::uppercase)
                    ?: ("AgentHub only removes healthy links and copies it manages. This one is shared some other way " +
                        "or needs Resync / Repair, so it stays as it is.")
            }
        }
    }

    val selectedTargetIds: Set<String> get() = checkboxes.filter { (_, box) -> box.isSelected }.mapTo(mutableSetOf()) { (id, _) -> id }

    init {
        title = dialogTitle
        setOKButtonText(okButtonText)
        checkboxes.forEach { (_, box) -> box.addItemListener { updateOkEnabled() } }
        updateOkEnabled()
        init()
    }

    private fun updateOkEnabled() {
        isOKActionEnabled = (allowEmpty || selectedTargetIds.isNotEmpty()) &&
            (!requireChange || selectedTargetIds != initiallyChecked)
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(8)
        if (checkboxes.isEmpty()) {
            add(JBLabel(emptyMessage), BorderLayout.NORTH)
        } else {
            val notices = AgentHubUiComponents.verticalBox()
            hint?.let { text ->
                notices.add(
                    JBLabel("<html><body style='width: ${JBUI.scale(HINT_WIDTH)}px'>$text</body></html>").apply {
                        foreground = com.intellij.ui.JBColor.GRAY
                        alignmentX = JComponent.LEFT_ALIGNMENT
                        border = JBUI.Borders.emptyBottom(AgentHubUiComponents.CONTROL_GAP)
                    },
                )
            }
            warningHtml?.let { html ->
                notices.add(
                    JBLabel(html, AllIcons.General.Warning, JLabel.LEADING).apply {
                        foreground = SYSTEM_WARNING_COLOR
                        alignmentX = JComponent.LEFT_ALIGNMENT
                        border = JBUI.Borders.emptyBottom(AgentHubUiComponents.CONTROL_GAP)
                    },
                )
            }
            if (notices.componentCount > 0) add(notices, BorderLayout.NORTH)
            val list = AgentHubUiComponents.verticalBox()
            val grouped = checkboxes.filter { (id, _) -> groupTitle != null && id in groupedIds }
            val others = checkboxes - grouped.toSet()
            if (grouped.isNotEmpty()) {
                list.add(AgentHubUiComponents.sectionHeader(groupTitle!!).apply {
                    border = JBUI.Borders.emptyBottom(AgentHubUiComponents.SMALL_GAP)
                })
                grouped.forEach { (id, box) -> list.add(indented(targetRow(id, box))) }
                if (others.isNotEmpty()) {
                    list.add(AgentHubUiComponents.sectionHeader("Other agents").apply {
                        border = JBUI.Borders.empty(AgentHubUiComponents.CONTROL_GAP, 0, AgentHubUiComponents.SMALL_GAP, 0)
                    })
                }
            }
            others.forEach { (id, box) -> list.add(if (grouped.isEmpty()) targetRow(id, box) else indented(targetRow(id, box))) }
            add(list, BorderLayout.CENTER)
        }
    }

    private fun indented(row: JComponent): JComponent = JPanel(BorderLayout()).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = JBUI.Borders.emptyLeft(AgentHubUiComponents.TEXT_LEFT_INSET)
        add(row, BorderLayout.CENTER)
    }

    private fun targetRow(agentId: String, box: JCheckBox): JComponent = AgentHubUiComponents.agentChoiceRow(
        box,
        when {
            agentId in groupedIds -> displayName(agentId)
            agentId in lockedIds -> "${displayName(agentId)} (${lockedNotes[agentId] ?: "can't be changed here"})"
            else -> displayName(agentId)
        },
        agentIcon(agentId),
    )

    private companion object {
        const val HINT_WIDTH = 340
    }
}

internal class SkillSyncPreviewDialog(
    project: Project,
    private val model: SkillSyncPreviewModel,
) : DialogWrapper(project, true) {
    init {
        title = model.title
        setOKButtonText("Apply to ${model.changeCount} ${if (model.changeCount == 1) "target" else "targets"}")
        init()
        isOKActionEnabled = model.canApply
    }

    override fun createCenterPanel(): JComponent {
        val content = AgentHubUiComponents.verticalBoxPanel()
        content.add(label("Context", model.context))
        content.add(label("Shared source", model.canonicalPath, singleLine = true))
        model.backupPath?.let { content.add(label("Backups", it, singleLine = true)) }
        content.add(AgentHubUiComponents.sectionHeader("Targets"))
        if (model.targets.isEmpty()) {
            content.add(label("", "No applicable targets were found."))
        } else {
            model.targets.forEachIndexed { index, target ->
                if (index > 0) content.add(AgentHubUiComponents.detailDivider())
                content.add(targetPanel(target))
            }
        }
        if (model.warnings.isNotEmpty()) content.add(label("Warnings", model.warnings.joinToString("\n")))
        return AgentHubUiComponents.verticalScrollPane(content, 680, 380)
    }

    private fun targetPanel(target: SkillSyncPreviewTarget): JComponent = AgentHubUiComponents.detailBlock().apply {
        add(AgentHubUiComponents.sectionHeader("${target.agentName} · ${target.currentState}").apply {
            icon = AgentHubUiComponents.faviconFor(target.agentId)
        })
        add(JBLabel(target.plannedChange).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        target.targetPath?.let { add(AgentHubUiComponents.pathText(it)) }
        val mode = listOfNotNull(
            target.requestedMode?.let { "Requested: $it" },
            target.effectiveMode?.let { "Effective: $it" },
        ).joinToString(" · ")
        if (mode.isNotEmpty()) add(JBLabel(mode).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
    }

    private fun label(title: String, value: String, singleLine: Boolean = false): JComponent =
        object : JPanel(BorderLayout()) {
            // BorderLayout.maximumLayoutSize() always reports (MAX_VALUE, MAX_VALUE) regardless of
            // content, so inside the outer BoxLayout.Y_AXIS column this block would otherwise
            // swallow all leftover vertical space. Computed fresh on every call (not frozen via
            // setMaximumSize) because the wrapped text's preferred height below only becomes
            // accurate once real layout — and thus a real width — is available.
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(AgentHubUiComponents.CONTROL_GAP)
            add(AgentHubUiComponents.sectionHeader(title), BorderLayout.NORTH)
            add(
                if (singleLine) {
                    AgentHubUiComponents.pathText(value)
                } else {
                    AgentHubUiComponents.wrappingStatusText().apply { text = value }
                },
                BorderLayout.CENTER,
            )
        }
}

/** Explicit preview gate between choosing Undo on a history entry and mutating the filesystem. */
internal class SkillUndoPreviewDialog(
    project: Project,
    private val preview: UndoPreview,
    private val displayName: (String) -> String,
    private val agentIcon: (String) -> Icon? = AgentHubUiComponents::faviconFor,
) : DialogWrapper(project, true) {
    init {
        title = "Undo Synchronization"
        setOKButtonText("Undo Operation")
        init()
    }

    // Same section layout as the other plan dialogs: bold headers, agents with icons, paths in gray.
    override fun createCenterPanel(): JComponent {
        val content = AgentHubUiComponents.verticalBoxPanel()
        content.add(AgentHubUiComponents.sectionHeader("This will reverse operation ${preview.operationId}"))
        content.add(Box.createVerticalStrut(AgentHubUiComponents.CONTROL_GAP))
        content.add(AgentHubUiComponents.sectionHeader("Skill"))
        content.add(JBLabel(preview.skillId).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        content.add(Box.createVerticalStrut(AgentHubUiComponents.CONTROL_GAP))
        content.add(AgentHubUiComponents.sectionHeader("Agents"))
        content.add(agentRow(preview.affectedAgents.sorted()))
        content.add(Box.createVerticalStrut(AgentHubUiComponents.CONTROL_GAP))
        content.add(AgentHubUiComponents.sectionHeader("Restorable backups"))
        content.add(JBLabel(preview.backupCount.toString()).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        content.add(Box.createVerticalStrut(AgentHubUiComponents.CONTROL_GAP))
        content.add(AgentHubUiComponents.sectionHeader("Paths that will be checked and reversed"))
        preview.affectedPaths.forEach { content.add(AgentHubUiComponents.pathText(it)) }
        return content
    }

    private fun agentRow(agentIds: List<String>): JComponent =
        JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(AgentHubUiComponents.CONTROL_GAP), 0, JBUI.scale(DIALOG_ROW_WIDTH))).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            isOpaque = false
            if (agentIds.isEmpty()) add(JBLabel("none"))
            agentIds.forEach { add(JLabel(displayName(it), agentIcon(it), JLabel.LEADING)) }
        }
}

/** The plan of a bulk operation by directory; this dialog never mutates the filesystem. */
internal class SkillBulkPlanDialog(
    project: Project,
    private val model: SkillBulkPlanModel,
) : DialogWrapper(project, true) {
    init {
        title = model.title
        setOKButtonText(model.applyLabel)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val content = AgentHubUiComponents.verticalBoxPanel()
        content.add(wrapped(model.summary).apply { border = JBUI.Borders.emptyBottom(AgentHubUiComponents.CONTROL_GAP) })
        model.backupPath?.let {
            content.add(AgentHubUiComponents.sectionHeader("Backups"))
            content.add(AgentHubUiComponents.pathText(it))
            content.add(Box.createVerticalStrut(AgentHubUiComponents.CONTROL_GAP))
        }
        content.add(AgentHubUiComponents.sectionHeader("Directories"))
        if (model.directories.isEmpty()) {
            content.add(JBLabel("No directories are affected.").apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        } else {
            model.directories.forEachIndexed { index, directory ->
                if (index > 0) content.add(AgentHubUiComponents.detailDivider())
                content.add(directoryBlock(directory))
            }
        }
        if (model.warnings.isNotEmpty()) {
            content.add(Box.createVerticalStrut(AgentHubUiComponents.CONTROL_GAP))
            content.add(AgentHubUiComponents.sectionHeader("Warnings"))
            model.warnings.forEach { content.add(wrapped(it).apply { border = JBUI.Borders.emptyBottom(AgentHubUiComponents.SMALL_GAP) }) }
        }
        return AgentHubUiComponents.verticalScrollPane(content, 680, 380)
    }

    private fun directoryBlock(directory: BulkPlanDirectory): JComponent = AgentHubUiComponents.detailBlock().apply {
        add(AgentHubUiComponents.sectionHeader(directory.change.caption))
        add(AgentHubUiComponents.pathText(directory.path))
        if (directory.agentIds.isNotEmpty()) {
            add(
                JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(AgentHubUiComponents.CONTROL_GAP), 0, JBUI.scale(DIALOG_ROW_WIDTH))).apply {
                    alignmentX = JComponent.LEFT_ALIGNMENT
                    isOpaque = false
                    directory.agentIds.forEach {
                        add(JLabel(AgentHubUiComponents.displayName(it), AgentHubUiComponents.faviconFor(it), JLabel.LEADING))
                    }
                },
            )
        }
        add(wrapped("${directory.skills.size} ${if (directory.skills.size == 1) "skill" else "skills"}: ${directory.skills.joinToString(", ")}"))
    }

    /**
     * Wrapped text at a fixed width: unlike a width-aware text area its height never depends on the
     * (not yet assigned) width of its parent, so nothing is cut off inside the scroll pane.
     */
    private fun wrapped(text: String): JBLabel = JBLabel(
        "<html><body style='width: ${JBUI.scale(WRAP_WIDTH)}px'>${StringUtil.escapeXmlEntities(text)}</body></html>",
    ).apply { alignmentX = JComponent.LEFT_ALIGNMENT }

    private companion object {
        const val WRAP_WIDTH = 600
    }
}

/** Selection/review gate for duplicate migration; this dialog never mutates the filesystem. */
internal class SkillBulkMigrationDialog(
    project: Project,
    candidates: List<BulkMigrationCandidate>,
    private val reviewPlan: Boolean = true,
) : DialogWrapper(project, true) {
    private class Entry(val candidate: BulkMigrationCandidate, val box: JCheckBox)

    private val entries = candidates.map { candidate ->
        Entry(candidate, JCheckBox(candidate.skill.name, true).apply { font = font.deriveFont(Font.BOLD) })
    }
    private val countLabel = JBLabel().apply { foreground = JBColor.GRAY }

    val selectedCandidates: List<BulkMigrationCandidate>
        get() = entries.filter { it.box.isSelected }.map { it.candidate }

    init {
        title = "Migrate Duplicate Skills"
        entries.forEach { it.box.addItemListener { updateSummary() } }
        updateSummary()
        init()
    }

    private fun updateSummary() {
        val selected = entries.count { it.box.isSelected }
        countLabel.text = "$selected of ${entries.size} selected"
        // With plan review on, the next step is the by-directory plan; otherwise OK applies the selection.
        setOKButtonText(if (reviewPlan) "Review Plan" else if (selected == 1) "Migrate 1 Skill" else "Migrate $selected Skills")
        isOKActionEnabled = selected > 0
    }

    private fun setAll(selected: Boolean) = entries.forEach { it.box.isSelected = selected }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(AgentHubUiComponents.CONTROL_GAP))).apply {
        border = JBUI.Borders.empty(AgentHubUiComponents.PANEL_INSET)
        val top = JPanel(BorderLayout(0, JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))
        top.add(
            JBLabel(
                "<html><body style='width: ${JBUI.scale(DIALOG_ROW_WIDTH)}px'>These skills exist as identical copies in " +
                    "several agents. Migrating moves one copy into the shared folder and links the other agents to it. " +
                    "Existing content is backed up first, and one failing skill does not stop the rest.</body></html>",
            ),
            BorderLayout.NORTH,
        )
        top.add(
            JPanel(BorderLayout()).apply {
                add(
                    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(AgentHubUiComponents.CONTROL_GAP), 0)).apply {
                        add(com.intellij.ui.components.ActionLink("Select all") { setAll(true) })
                        add(com.intellij.ui.components.ActionLink("Clear") { setAll(false) })
                    },
                    BorderLayout.WEST,
                )
                add(countLabel, BorderLayout.EAST)
            },
            BorderLayout.SOUTH,
        )
        add(top, BorderLayout.NORTH)
        val list = AgentHubUiComponents.verticalBox()
        entries.forEachIndexed { index, entry ->
            if (index > 0) list.add(AgentHubUiComponents.detailDivider())
            list.add(entryRow(entry))
        }
        add(
            JBScrollPane(JPanel(BorderLayout()).apply { add(list, BorderLayout.NORTH) }).apply {
                border = JBUI.Borders.customLine(JBColor.border())
                preferredSize = Dimension(JBUI.scale(DIALOG_ROW_WIDTH + 40), JBUI.scale(300))
            },
            BorderLayout.CENTER,
        )
    }

    /** Skill name, its description, and what happens: which copy moves to the shared folder and who gets linked. */
    private fun entryRow(entry: Entry): JComponent {
        val candidate = entry.candidate
        val promote = candidate.requests.filterIsInstance<SkillSyncRequest.PromoteSkill>().firstOrNull()
        val linked = candidate.requests.filterIsInstance<SkillSyncRequest.ShareSkill>().map { it.targetAgentId }
        val fallback = candidate.skill.sources.mapNotNull { it.agentId }.distinct().sorted()
        val source = promote?.sourceAgentId ?: fallback.firstOrNull()
        // Only agents the plan really links. The agents that merely list the same folder (Cursor, Kilo… read
        // ~/.claude/skills) are not linked and must not be shown or warned about.
        val linkedIds = linked
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, AgentHubUiComponents.CONTROL_GAP)
            add(entry.box.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
            val indent = JBUI.Borders.emptyLeft(INDENT)
            candidate.skill.description?.takeIf(String::isNotBlank)?.let { description ->
                add(AgentHubUiComponents.singleLineText(description.lineSequence().first().take(160), DIALOG_ROW_WIDTH).apply {
                    foreground = JBColor.GRAY
                    border = indent
                    alignmentX = JComponent.LEFT_ALIGNMENT
                })
            }
            source?.let { add(agentLine("Moves to shared from", listOf(it), indent)) }
            if (linkedIds.isNotEmpty()) add(agentLine("Then linked for", linkedIds, indent))
            // A vendor-provided copy can be moved or replaced by a link; say so, as the single-skill dialogs do.
            val promotedPath = promote?.sourcePath
            val systemAgents = candidate.skill.sources
                .filter { it.system && (it.agentId in linkedIds || runCatching { Path.of(it.path) }.getOrNull() == promotedPath) }
                .mapNotNull { it.agentId }
                .distinct()
                .sorted()
            if (systemAgents.isNotEmpty()) {
                val names = systemAgents.joinToString(", ") { AgentHubUiComponents.displayName(it) }
                add(
                    JBLabel(
                        "<html><body style='width: ${JBUI.scale(DIALOG_ROW_WIDTH - INDENT)}px'><b>Includes a vendor-provided copy</b> " +
                            "($names). It is replaced by a link to the shared skill (backed up first), and the agent may restore " +
                            "it on its next update.</body></html>",
                        AllIcons.General.Warning,
                        JLabel.LEADING,
                    ).apply {
                        foreground = SYSTEM_WARNING_COLOR
                        border = indent
                        alignmentX = JComponent.LEFT_ALIGNMENT
                    },
                )
            }
        }
    }

    private fun agentLine(caption: String, agentIds: List<String>, indent: Border): JComponent =
        JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(AgentHubUiComponents.CONTROL_GAP), 0, JBUI.scale(DIALOG_ROW_WIDTH))).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            isOpaque = false
            border = indent
            add(JBLabel(caption).apply { foreground = JBColor.GRAY })
            agentIds.forEach { id ->
                add(JLabel(AgentHubUiComponents.displayName(id), AgentHubUiComponents.faviconFor(id), JLabel.LEADING))
            }
        }

    override fun doValidate(): ValidationInfo? =
        if (selectedCandidates.isEmpty()) ValidationInfo("Select at least one skill.") else null

    private companion object {
        const val INDENT = 24
    }
}

/** Selection/review gate for the redundant-copy clean-up; this dialog never mutates the filesystem. */
internal class SkillRedundantCopyDialog(
    project: Project,
    work: List<RedundantCopyWork>,
    private val reviewPlan: Boolean = true,
) : DialogWrapper(project, true) {
    private class Entry(val work: RedundantCopyWork, val box: JCheckBox)

    private val entries = work.map { item ->
        val where = if (item.context.scope == SkillScope.PROJECT) item.context.project?.let { " (${it.name})" }.orEmpty() else ""
        Entry(item, JCheckBox(item.candidate.skill.name + where, true).apply { font = font.deriveFont(Font.BOLD) })
    }
    private val countLabel = JBLabel().apply { foreground = JBColor.GRAY }

    val selectedWork: List<RedundantCopyWork>
        get() = entries.filter { it.box.isSelected }.map { it.work }

    init {
        title = "Clean Up Redundant Copies"
        entries.forEach { it.box.addItemListener { updateSummary() } }
        updateSummary()
        init()
    }

    private fun updateSummary() {
        val selected = entries.count { it.box.isSelected }
        countLabel.text = "$selected of ${entries.size} selected"
        setOKButtonText(if (reviewPlan) "Review Plan" else if (selected == 1) "Clean Up 1 Skill" else "Clean Up $selected Skills")
        isOKActionEnabled = selected > 0
    }

    private fun setAll(selected: Boolean) = entries.forEach { it.box.isSelected = selected }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(AgentHubUiComponents.CONTROL_GAP))).apply {
        border = JBUI.Borders.empty(AgentHubUiComponents.PANEL_INSET)
        val top = JPanel(BorderLayout(0, JBUI.scale(AgentHubUiComponents.CONTROL_GAP)))
        top.add(
            JBLabel(
                "<html><body style='width: ${JBUI.scale(DIALOG_ROW_WIDTH)}px'>These skills are already shared. A link is only unlinked (the shared skill is never touched), a copy is " +
                    "removed after a backup, and an identical copy of an agent that cannot read the shared folder is replaced by a link. " +
                    "Copies that differ are never touched.</body></html>",
            ),
            BorderLayout.NORTH,
        )
        top.add(
            JPanel(BorderLayout()).apply {
                add(
                    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(AgentHubUiComponents.CONTROL_GAP), 0)).apply {
                        add(com.intellij.ui.components.ActionLink("Select all") { setAll(true) })
                        add(com.intellij.ui.components.ActionLink("Clear") { setAll(false) })
                    },
                    BorderLayout.WEST,
                )
                add(countLabel, BorderLayout.EAST)
            },
            BorderLayout.SOUTH,
        )
        add(top, BorderLayout.NORTH)
        val list = AgentHubUiComponents.verticalBox()
        entries.forEachIndexed { index, entry ->
            if (index > 0) list.add(AgentHubUiComponents.detailDivider())
            list.add(entryRow(entry))
        }
        add(
            JBScrollPane(JPanel(BorderLayout()).apply { add(list, BorderLayout.NORTH) }).apply {
                border = JBUI.Borders.customLine(JBColor.border())
                preferredSize = Dimension(JBUI.scale(DIALOG_ROW_WIDTH + 40), JBUI.scale(300))
            },
            BorderLayout.CENTER,
        )
    }

    private fun entryRow(entry: Entry): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, AgentHubUiComponents.CONTROL_GAP)
        add(entry.box.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        entry.work.candidate.skill.description?.takeIf(String::isNotBlank)?.let { description ->
            add(AgentHubUiComponents.singleLineText(description.lineSequence().first().take(160), DIALOG_ROW_WIDTH).apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.emptyLeft(INDENT)
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
        }
        add(
            JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(AgentHubUiComponents.CONTROL_GAP), 0, JBUI.scale(DIALOG_ROW_WIDTH))).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                isOpaque = false
                border = JBUI.Borders.emptyLeft(INDENT)
                // Said exactly: a link is only unlinked, a copy is a real folder that is removed (after a backup).
                val (linked, copies) = entry.work.candidate.agentIds.partition { it in entry.work.candidate.linkedAgentIds }
                listOf(
                    "Removes the link in" to linked,
                    "Removes the copy in" to copies,
                    "Replaces the copy with a link in" to entry.work.candidate.convertAgentIds,
                ).forEach { (caption, ids) ->
                    if (ids.isEmpty()) return@forEach
                    add(JBLabel(caption).apply { foreground = JBColor.GRAY })
                    ids.forEach { id ->
                        add(JLabel(AgentHubUiComponents.displayName(id), AgentHubUiComponents.faviconFor(id), JLabel.LEADING))
                    }
                }
            },
        )
    }

    override fun doValidate(): ValidationInfo? =
        if (selectedWork.isEmpty()) ValidationInfo("Select at least one skill.") else null

    private companion object {
        const val INDENT = 24
    }
}

/** Picks one on-disk backup to restore; the confirmation copy updates as the selection changes. */
internal class SkillRestoreBackupDialog(
    project: Project,
    records: List<StoredBackupRecord>,
    displayName: (String) -> String,
    agentIcon: (String) -> Icon? = { null },
) : DialogWrapper(project, true) {
    private val choices = records.map { RecordChoice(it, displayName(it.agentId), agentIcon(it.agentId)) }
    private val backupChoice = JComboBox(choices.toTypedArray())
    private val backedUpAtLabel = JBLabel().apply { alignmentX = JComponent.LEFT_ALIGNMENT }
    private val restorePathLabel = AgentHubUiComponents.pathText("")
    private val currentContentLabel = JBLabel().apply { alignmentX = JComponent.LEFT_ALIGNMENT }

    val selectedRecord: StoredBackupRecord? get() = (backupChoice.selectedItem as? RecordChoice)?.record

    init {
        title = "Restore Backup"
        setOKButtonText("Restore")
        isOKActionEnabled = choices.isNotEmpty()
        backupChoice.accessibleContext.accessibleName = "Backup to restore"
        backupChoice.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                selected: Boolean,
                focus: Boolean,
            ): Component = super.getListCellRendererComponent(list, value, index, selected, focus).also {
                icon = (value as? RecordChoice)?.icon
            }
        }
        backupChoice.addActionListener { renderImpact() }
        renderImpact()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val content = AgentHubUiComponents.verticalBoxPanel()
        if (choices.isEmpty()) {
            content.add(JBLabel("No backups are available for this skill."))
        } else {
            // Same section layout as the plan dialogs: bold headers, the path in the shared darker gray.
            content.add(AgentHubUiComponents.sectionHeader("Backup"))
            content.add(backupChoice.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
            content.add(backedUpAtLabel)
            content.add(Box.createVerticalStrut(AgentHubUiComponents.CONTROL_GAP))
            content.add(AgentHubUiComponents.sectionHeader("Restores to"))
            content.add(restorePathLabel)
            content.add(currentContentLabel)
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(560), preferredSize.height)
            add(content, BorderLayout.NORTH)
        }
    }

    // Read-only preview text only — a deliberate, documented exception to the facade-only rule
    // (it only reads and never writes); the actual restore still goes through
    // SkillMutationController → SkillSyncApplicationService like every other action.
    private fun renderImpact() {
        val record = selectedRecord ?: return
        val currentExists = runCatching { Files.exists(record.backup.originalPath, LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false)
        val backedUpAt = AgentHubUiFormat.dateTime.format(record.backup.createdAt)
        backedUpAtLabel.text = "Version from $backedUpAt"
        restorePathLabel.text = record.backup.originalPath.toString()
        restorePathLabel.toolTipText = restorePathLabel.text
        currentContentLabel.text =
            if (currentExists) "The current content there will be backed up first." else "Nothing currently exists there to back up."
    }

    private data class RecordChoice(val record: StoredBackupRecord, val label: String, val icon: Icon?) {
        override fun toString(): String = "$label · ${AgentHubUiFormat.dateTime.format(record.backup.createdAt)}"
    }
}

/** Edits the persisted [SkillSyncSettings] defaults. */
internal class SkillSyncSettingsDialog(
    project: Project,
    private val current: SkillSyncSettings,
) : DialogWrapper(project, true) {
    private val linkOption = JRadioButton("Link (recommended)")
    private val copyOption = JRadioButton("Copy")
    private val backupCheckbox = JCheckBox("Back up content before replacing it", current.backupBeforeReplacement)
    private val manageExistingCheckbox = JCheckBox("Also remove links and copies AgentHub did not create", current.manageExistingTargets)
    private val reviewPlanCheckbox = JCheckBox("Review each change before applying it", current.reviewPlanBeforeApplying)

    var result: SkillSyncSettings? = null
        private set

    init {
        title = "Skill Sync Settings"
        setOKButtonText("Save")
        ButtonGroup().apply { add(linkOption); add(copyOption) }
        when (current.preferredSyncMode) {
            SkillSyncMode.SYMLINK -> linkOption.isSelected = true
            SkillSyncMode.COPY -> copyOption.isSelected = true
        }
        init()
    }

    override fun createCenterPanel(): JComponent = AgentHubUiComponents.verticalBoxPanel().apply {
        val gap = AgentHubUiComponents.CONTROL_GAP * 2
        add(reviewPlanCheckbox.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        add(hint("Shows what will change and asks for confirmation. When off, changes are applied immediately (backups are still made). Choosing agents, versions or backups is always asked."))
        add(Box.createVerticalStrut(gap))
        add(AgentHubUiComponents.sectionHeader("Sharing mode"))
        add(linkOption.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        add(copyOption.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        add(hint("Used when you share a skill or move it to the shared folder. Link points each agent at the shared skill (a symlink, or a junction on Windows); Copy duplicates the files. Where linking is not possible, a copy is used."))
        add(Box.createVerticalStrut(gap))
        add(backupCheckbox.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        add(hint("Needed for Undo and Restore Backup. When off, replaced content cannot be restored, and Undo can only remove what a change created."))
        add(Box.createVerticalStrut(gap))
        add(manageExistingCheckbox.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        add(hint("Applies when you uncheck an agent in Share with… or use Stop Sharing. Off: only links and copies AgentHub created are removed. On: an existing link to the shared skill, or a copy identical to it, is removed too, after a backup. A copy with different content is never removed."))
        add(Box.createVerticalStrut(gap))
        add(AgentHubUiComponents.sectionHeader("Data location"))
        val location = AgentHubStorage.home()?.root?.toString()
        add(AgentHubUiComponents.pathText(location?.let { abbreviateHome(it, System.getProperty("user.home")) } ?: "In memory for this session").apply {
            toolTipText = location
        })
        add(hint("Settings, history and backups are shared by all JetBrains IDEs."))
    }

    private fun hint(text: String) = JBLabel("<html><body style='width: ${JBUI.scale(HINT_WIDTH)}px'>$text</body></html>").apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        foreground = JBColor.GRAY
        border = JBUI.Borders.emptyTop(AgentHubUiComponents.SMALL_GAP)
    }

    override fun doOKAction() {
        result = SkillSyncSettings(
            preferredSyncMode = if (linkOption.isSelected) SkillSyncMode.SYMLINK else SkillSyncMode.COPY,
            backupBeforeReplacement = backupCheckbox.isSelected,
            manageExistingTargets = manageExistingCheckbox.isSelected,
            reviewPlanBeforeApplying = reviewPlanCheckbox.isSelected,
        )
        super.doOKAction()
    }
}

/** Replaces the user's home directory prefix with `~` and uses forward slashes, so a long absolute path reads short. */
internal fun abbreviateHome(path: String, home: String?): String {
    val normalized = path.replace('\\', '/')
    val normalizedHome = home?.replace('\\', '/')?.trimEnd('/')
    if (normalizedHome.isNullOrEmpty() || !normalized.startsWith("$normalizedHome/")) return normalized
    return "~" + normalized.removePrefix(normalizedHome)
}

private const val HINT_WIDTH = 380

/** Widest a wrapping agent-icon row asks for inside a dialog, so many agents wrap instead of widening it. */
private const val DIALOG_ROW_WIDTH = 460
