package com.shutterstar.agenthub.projects.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** What the compact layout's back bar shows for the currently selected item: its name and, optionally, an icon. */
internal data class DetailsTitle(
    val text: String,
    val icon: Icon? = null,
)

/**
 * Wide: [list] on the left, [details] on the right, via a properly-themed [JBSplitter] — a raw
 * `javax.swing.JSplitPane`'s divider ignores the IDE theme and stays white even in a dark theme
 * (the same bug [RoundedSelectionPanel] replaced `SelectablePanel` to avoid, just for a different
 * widget). Narrow: [list] alone, with a back bar swapping to full-width [details] once something
 * is selected — a splitter squeezed under [widthThreshold] leaves both sides too narrow to read,
 * so this switches to single-pane card navigation instead of shrinking the split further.
 *
 * The compact back bar carries the selected item's [DetailsTitle] next to the back button: with
 * the list hidden, nothing else on screen would say *which* project/agent/skill the details
 * belong to.
 *
 * The divider position comes from a [SharedSplitProportion] the three tabs share, and both sides get
 * the same minimum width whatever their content asks for — the splitter honours component minimums,
 * so a details page with a wide minimum would otherwise push its tab's divider away from the others.
 *
 * While a compact layout shows only the details, [onDetailsOnlyChanged] reports it: with the list
 * out of sight, the search/filter controls that narrow it are pointless and the host can hide them.
 *
 * The caller owns selection state; call [selectionChanged] whenever it changes (including
 * programmatically) so a narrow layout knows whether to jump straight to details, and
 * [showDetailsNow] for an explicit "open details" gesture (Enter, double-click) while compact.
 */
internal class ResponsiveMasterDetail(
    private val list: JComponent,
    private val details: JComponent,
    backButtonText: String,
    private val hasSelection: () -> Boolean,
    private val onBack: () -> Unit = {},
    private val selectionTitle: () -> DetailsTitle? = { null },
    private val widthThreshold: Int = 700,
    private val splitProportion: SharedSplitProportion = SharedSplitProportion(),
) {
    val component: JComponent get() = root

    val isCompact: Boolean get() = compact == true

    /** True while a compact layout shows the details page alone (the list is out of sight). */
    val isShowingDetailsOnly: Boolean get() = detailsOnly

    /** Called with the new value whenever [isShowingDetailsOnly] flips. */
    var onDetailsOnlyChanged: (Boolean) -> Unit = {}

    private val root = JPanel(BorderLayout())
    private val back = JButton(backButtonText, AllIcons.Actions.Back).apply {
        accessibleContext.accessibleName = "Back to $backButtonText"
    }
    private val titleLabel = JBLabel().apply {
        putClientProperty("html.disable", true)
        font = font.deriveFont(Font.BOLD)
        border = JBUI.Borders.emptyLeft(AgentHubUiComponents.CONTROL_GAP)
    }
    private val backBar = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(AgentHubUiComponents.CONTROL_GAP)
        add(back, BorderLayout.WEST)
        add(titleLabel, BorderLayout.CENTER)
    }
    private val splitter = JBSplitter(false, splitProportion.value)
    private var compact: Boolean? = null
    private var showingDetails = false
    private var detailsOnly = false
    private var renderedLayout: Int? = null

    init {
        list.minimumSize = Dimension(JBUI.scale(MIN_LIST_WIDTH), 0)
        details.minimumSize = Dimension(JBUI.scale(MIN_DETAILS_WIDTH), 0)
        splitter.border = null
        // A drag on this tab's divider moves every other tab's divider too (and vice versa).
        splitter.addPropertyChangeListener(Splitter.PROP_PROPORTION) { splitProportion.update(splitter.proportion) }
        splitProportion.addListener { value -> if (splitter.proportion != value) splitter.proportion = value }
        back.addActionListener {
            showingDetails = false
            render()
            onBack()
        }
        root.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = render()
        })
        render()
    }

    /**
     * Call after [list]'s selection changes for any reason, so a compact layout can react.
     *
     * This only ever *clears* [showingDetails] (when the selection disappears entirely, e.g. a
     * filter now matches nothing) — it deliberately never sets it. A programmatic auto-select
     * (populating the list for the first time) also "changes the selection", and forcing compact
     * mode straight to details for that would skip the list every time the panel first loads.
     * Jumping to details for a real user gesture is [showDetailsNow]'s job.
     */
    fun selectionChanged() {
        if (!hasSelection()) showingDetails = false
        render()
    }

    /** Explicitly reveal details while compact (Enter / double-click on an already-selected row). */
    fun showDetailsNow() {
        if (!isCompact) return
        showingDetails = true
        render()
        javax.swing.SwingUtilities.invokeLater { if (detailsOnly) back.requestFocusInWindow() }
    }

    /** Forces a compact layout back to the list, e.g. when the underlying context changed and the old selection is about to become stale. */
    fun showList() {
        showingDetails = false
        render()
    }

    private fun render() {
        val useCompact = root.width > 0 && root.width < JBUI.scale(widthThreshold)
        val changedMode = compact != useCompact
        compact = useCompact
        val showCompactDetails = useCompact && showingDetails && hasSelection()
        val layout = when {
            !useCompact -> WIDE_LAYOUT
            showCompactDetails -> COMPACT_DETAILS_LAYOUT
            else -> COMPACT_LIST_LAYOUT
        }
        if (showCompactDetails) {
            val title = selectionTitle()
            titleLabel.text = title?.text.orEmpty()
            titleLabel.icon = title?.icon
            titleLabel.toolTipText = title?.text
        }
        // Component resize events are frequent during startup. Reparenting an unchanged pane
        // recursively calls addNotify on its entire tree and can freeze the EDT for seconds.
        if (layout != renderedLayout) {
            root.removeAll()
            if (useCompact) {
                // Clear Splitter bookkeeping before moving either pane out of it. Otherwise a
                // later compact-to-wide transition can leave one pane detached.
                splitter.firstComponent = null
                splitter.secondComponent = null
                if (showCompactDetails) {
                    root.add(backBar, BorderLayout.NORTH)
                    root.add(details, BorderLayout.CENTER)
                } else {
                    root.add(list, BorderLayout.CENTER)
                }
            } else {
                splitter.firstComponent = list
                splitter.secondComponent = details
                root.add(splitter, BorderLayout.CENTER)
                if (changedMode) splitter.proportion = splitProportion.value
            }
            renderedLayout = layout
            root.revalidate()
            root.repaint()
        }
        if (showCompactDetails != detailsOnly) {
            detailsOnly = showCompactDetails
            onDetailsOnlyChanged(showCompactDetails)
        }
    }

    private companion object {
        const val WIDE_LAYOUT = 0
        const val COMPACT_LIST_LAYOUT = 1
        const val COMPACT_DETAILS_LAYOUT = 2
        const val MIN_LIST_WIDTH = 200
        const val MIN_DETAILS_WIDTH = 230
    }
}
