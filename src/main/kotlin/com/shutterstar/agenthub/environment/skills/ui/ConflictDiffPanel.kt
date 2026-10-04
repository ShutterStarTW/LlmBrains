package com.shutterstar.agenthub.environment.skills.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.UserFacingError
import com.shutterstar.agenthub.projects.ui.AgentHubUiComponents
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * The read-only "what is different" view of a skill conflict: the files that differ between two
 * skill directories (each labelled with which side has it) above the real IDE side-by-side diff of
 * the selected file. Reads only; the calling dialog decides what to do with the answer.
 */
internal class ConflictDiffPanel(
    private val project: Project,
    parent: Disposable,
    private var leftLabel: String,
    private var rightLabel: String,
    private var leftDir: Path,
    private var rightDir: Path,
    /** Called with false while a comparison runs or when it failed, true once the two versions were compared safely. */
    private val onCompared: (Boolean) -> Unit = {},
) : JPanel(BorderLayout(0, JBUI.scale(AgentHubUiComponents.CONTROL_GAP))) {
    private val model = DefaultListModel<ConflictFileEntry>()
    private val list = JBList(model)
    private val headline = JBLabel()
    private val diffPanel = DiffManager.getInstance().createRequestPanel(project, parent, null)

    /** Bumped on every side/selection change so a slow background read never overwrites newer content. */
    private var generation = 0

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                selected: Boolean,
                focus: Boolean,
            ): Component = super.getListCellRendererComponent(list, describe(value as ConflictFileEntry), index, selected, focus)
        }
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) list.selectedValue?.let(::showFile)
        }
        val top = JPanel(BorderLayout(0, JBUI.scale(AgentHubUiComponents.SMALL_GAP))).apply {
            add(headline, BorderLayout.NORTH)
            add(
                JBScrollPane(list).apply { preferredSize = Dimension(JBUI.scale(400), JBUI.scale(96)) },
                BorderLayout.CENTER,
            )
        }
        add(top, BorderLayout.NORTH)
        add(diffPanel.component.apply { preferredSize = Dimension(JBUI.scale(760), JBUI.scale(320)) }, BorderLayout.CENTER)
    }

    private fun describe(entry: ConflictFileEntry): String = when (entry.status) {
        ConflictFileStatus.MODIFIED -> "Differs: ${entry.relativePath}"
        ConflictFileStatus.MISSING -> "Only in $leftLabel: ${entry.relativePath}"
        ConflictFileStatus.ADDED -> "Only in $rightLabel: ${entry.relativePath}"
    }

    /** Shows already-compared [entries] (the caller compared them off the EDT). */
    fun setEntries(entries: List<ConflictFileEntry>) {
        generation++
        model.clear()
        entries.forEach(model::addElement)
        headline.text = when (entries.size) {
            0 -> "The two versions have identical files."
            1 -> "1 file differs between $leftLabel and $rightLabel:"
            else -> "${entries.size} files differ between $leftLabel and $rightLabel:"
        }
        if (entries.isEmpty()) show(MessageDiffRequest("No file differences detected.")) else list.selectedIndex = 0
    }

    /** Points the panel at two other directories and compares them in the background. */
    fun compare(newLeftLabel: String, newLeftDir: Path, newRightLabel: String, newRightDir: Path) {
        leftLabel = newLeftLabel
        rightLabel = newRightLabel
        leftDir = newLeftDir
        rightDir = newRightDir
        val ticket = ++generation
        onCompared(false)
        model.clear()
        headline.text = "Comparing $leftLabel and $rightLabel…"
        show(MessageDiffRequest("Comparing…"))
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { SkillConflictDiff.compareDirectories(newLeftDir, newRightDir) }
            ApplicationManager.getApplication().invokeLater({
                if (ticket != generation) return@invokeLater
                result.fold(
                    onSuccess = { entries ->
                        setEntries(entries)
                        onCompared(true)
                    },
                    onFailure = { error ->
                        headline.text = "The versions could not be compared."
                        show(MessageDiffRequest(UserFacingError.describe("The versions cannot be compared safely", error)))
                    },
                )
            }, ModalityState.any())
        }
    }

    private fun showFile(entry: ConflictFileEntry) {
        val ticket = ++generation
        val left = leftDir
        val right = rightDir
        ApplicationManager.getApplication().executeOnPooledThread {
            val request = runCatching {
                val leftText = if (entry.status == ConflictFileStatus.ADDED) "" else SkillConflictDiff.readText(left, entry.relativePath)
                val rightText = if (entry.status == ConflictFileStatus.MISSING) "" else SkillConflictDiff.readText(right, entry.relativePath)
                leftText to rightText
            }
            ApplicationManager.getApplication().invokeLater({
                if (ticket != generation) return@invokeLater
                val factory = DiffContentFactory.getInstance()
                show(
                    request.fold(
                        onSuccess = { (leftText, rightText) ->
                            SimpleDiffRequest(
                                entry.relativePath,
                                factory.create(project, leftText, PlainTextFileType.INSTANCE),
                                factory.create(project, rightText, PlainTextFileType.INSTANCE),
                                leftLabel,
                                rightLabel,
                            )
                        },
                        onFailure = { MessageDiffRequest(UserFacingError.describe("The file cannot be displayed safely", it)) },
                    ),
                )
            }, ModalityState.any())
        }
    }

    private fun show(request: DiffRequest) = diffPanel.setRequest(request)
}
