package com.shutterstar.agenthub.projects.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.projects.model.AgentSession
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager

/** What the Sessions list can do with one session; every callback is wired by the tool window. */
internal class SessionActions(
    /** Reopen the session in its own agent (native resume). Only offered when the row is resumable. */
    val resume: (AgentSession) -> Unit = {},
    /** Open the session's transcript/source file in the IDE editor. */
    val openTranscript: (AgentSession) -> Unit = {},
    /** Reveal the transcript/source file in the OS file manager. */
    val reveal: (AgentSession) -> Unit = {},
)

/**
 * The Sessions tab of a project or agent: one flat, keyboard-navigable list of collapsible group
 * headers (agents in project context, projects in agent context) with the individual sessions
 * underneath an expanded group. Replaces the hand-built `BoxLayout` of `JLabel`s that created one
 * Swing component per session (unbounded for busy projects) and had neither hover nor keyboard
 * navigation between rows.
 *
 * Group headers deliberately carry no `▸/▾` marker: click/Enter toggles them, and the indented
 * session rows underneath are what shows a group is open. The most recently active group starts
 * expanded so the freshest sessions are visible without a click.
 *
 * Session rows carry two link-styled actions on the right — **Resume** (native resume, when
 * AgentHub knows the agent's resume command) and **Transcript** (open the source file). Clicks
 * on them are resolved by hit-testing the renderer, the same technique the other lists use to
 * forward per-icon tooltips; Enter resumes when possible and opens the transcript otherwise.
 */
internal class SessionsPanel(
    private val agentName: (String) -> String = AgentHubUiComponents::displayName,
    private val formatTime: (Instant) -> String = AgentHubUiFormat.dateTime::format,
    private val actions: SessionActions = SessionActions(),
) : JPanel(BorderLayout()) {
    private val model = DefaultListModel<SessionRowItem>()
    private val renderer = SessionRowRenderer()
    // Tooltip and link hit-testing lay out a renderer at cell bounds; keep that off the painted instance.
    private val hitTestRenderer = SessionRowRenderer()
    private val list = object : RendererToolTipList<SessionRowItem>(model) {
        override fun getToolTipText(event: MouseEvent): String? {
            val index = locationToIndex(event.point)
            var component = if (index >= 0) componentAt(index, event.point) else null
            while (component != null) {
                (component as? JComponent)?.toolTipText?.let { return it }
                component = component.parent
            }
            return null
        }
    }
    private val hover = ListHoverTracker(list)
    private var scope: DetailsScope? = null
    private var expandedKeys: Set<String> = emptySet()
    private val detailsScroll = DetailsStrip("Session details")

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) updateDetails()
        }
        // Expanded hover rows change the right edge and make Resume/Transcript jump.
        list.setExpandableItemsEnabled(false)
        list.cellRenderer = renderer
        list.emptyText.text = "Select a project"
        list.accessibleContext.accessibleName = "Sessions"
        ToolTipManager.sharedInstance().registerComponent(list)
        RowContextMenus.install(list) { item -> (item as? SessionRowItem.Session)?.let(::contextMenu) }
        list.addMouseListener(object : MouseAdapter() {

            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                val index = list.locationToIndex(event.point)
                if (index < 0 || list.getCellBounds(index, index)?.contains(event.point) != true) return
                when (val item = model.getElementAt(index)) {
                    is SessionRowItem.Group -> if (event.clickCount == 1) toggle(item)
                    is SessionRowItem.Session -> when (componentAt(index, event.point)?.name) {
                        RESUME_LINK -> if (item.resumable) actions.resume(item.session)
                        TRANSCRIPT_LINK -> actions.openTranscript(item.session)
                        else -> if (event.clickCount == 2) activate(item)
                    }
                }
            }

        })
        list.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                val index = list.locationToIndex(event.point)
                val session = if (index >= 0) model.getElementAt(index) as? SessionRowItem.Session else null
                // A hand cursor promises an action: only over a link that would actually do something.
                val overLink = session != null && when (componentAt(index, event.point)?.name) {
                    RESUME_LINK -> session.resumable
                    TRANSCRIPT_LINK -> session.session.sourcePath != null
                    else -> false
                }
                list.cursor = if (overLink) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        })
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "activate-session-row")
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "activate-session-row")
        list.actionMap.put("activate-session-row", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                when (val item = list.selectedValue) {
                    is SessionRowItem.Group -> toggle(item)
                    is SessionRowItem.Session -> activate(item)
                    null -> Unit
                }
            }
        })
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "open-session-transcript")
        list.actionMap.put("open-session-transcript", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                (list.selectedValue as? SessionRowItem.Session)?.takeIf { it.session.sourcePath != null }
                    ?.let { actions.openTranscript(it.session) }
            }
        })
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), "resume-session")
        list.actionMap.put("resume-session", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                (list.selectedValue as? SessionRowItem.Session)?.takeIf { it.resumable }
                    ?.let { actions.resume(it.session) }
            }
        })
        add(AgentHubUiComponents.alignedBorderlessScrollPane(list), BorderLayout.CENTER)
        add(detailsScroll, BorderLayout.SOUTH)
    }

    fun setScope(scope: DetailsScope?) {
        val changed = scope?.key != this.scope?.key
        this.scope = scope
        if (changed) {
            // Fresh entity: only its most recent group open. Same entity refreshed: keep the
            // user's expansion state. (Group order does not depend on expansion, so building
            // with nothing expanded is enough to find the first group.)
            expandedKeys = SessionListModel.defaultExpandedKeys(build(scope, emptySet()))
        }
        list.emptyText.text = when (scope) {
            null -> "Select a project"
            is DetailsScope.ForProject -> "No agent sessions recorded for this project."
            is DetailsScope.ForAgent -> "No sessions recorded for this agent."
        }
        render(preserveSelection = !changed)
        if (changed) AgentHubUiComponents.scrollToTop(list)
    }

    private fun build(scope: DetailsScope?, expanded: Set<String>): List<SessionRowItem> = when (scope) {
        null -> emptyList()
        is DetailsScope.ForProject -> SessionListModel.byAgent(scope.project, expanded, agentName, formatTime)
        is DetailsScope.ForAgent -> SessionListModel.byProject(scope.agentId, scope.projects, expanded, formatTime)
    }

    private fun activate(item: SessionRowItem.Session) {
        if (item.resumable) actions.resume(item.session) else actions.openTranscript(item.session)
    }

    private fun updateDetails() {
        val session = (list.selectedValue as? SessionRowItem.Session)?.session
        detailsScroll.showLines(session?.let { SessionDetailsModel.lines(it, formatTime) }.orEmpty())
        revalidate()
        repaint()
    }

    private fun toggle(group: SessionRowItem.Group) {
        expandedKeys = if (group.groupKey in expandedKeys) expandedKeys - group.groupKey else expandedKeys + group.groupKey
        render(preserveSelection = true)
    }

    private fun render(preserveSelection: Boolean) {
        val selectedKey = (list.selectedValue as? SessionRowItem.Group)?.groupKey
            ?: (list.selectedValue as? SessionRowItem.Session)?.session?.id
        model.removeAllElements()
        build(scope, expandedKeys).forEach(model::addElement)
        if (preserveSelection && selectedKey != null) {
            val index = (0 until model.size).firstOrNull { i ->
                when (val item = model.getElementAt(i)) {
                    is SessionRowItem.Group -> item.groupKey == selectedKey
                    is SessionRowItem.Session -> item.session.id == selectedKey
                }
            }
            if (index != null) list.selectedIndex = index
        } else {
            list.clearSelection()
        }
    }

    /** The deepest renderer child under [point] for row [index], after laying the renderer out at the cell's bounds. */
    private fun componentAt(index: Int, point: Point): Component? {
        val bounds = list.getCellBounds(index, index) ?: return null
        if (!bounds.contains(point)) return null
        val component = hitTestRenderer.getListCellRendererComponent(
            list, model.getElementAt(index), index, list.isSelectedIndex(index), false,
        )
        component.setBounds(0, 0, bounds.width, bounds.height)
        fun layout(target: Component) {
            target.doLayout()
            if (target is Container) target.components.forEach(::layout)
        }
        layout(component)
        return SwingUtilities.getDeepestComponentAt(component, point.x - bounds.x, point.y - bounds.y)
    }

    private fun contextMenu(item: SessionRowItem.Session): JPopupMenu = JPopupMenu().apply {
        add(JMenuItem("Resume in ${agentName(item.session.agentId)}").apply {
            isEnabled = item.resumable
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK)
            toolTipText = if (item.resumable) "Reopen this session with its own agent" else item.resumeBlockedReason ?: NOT_RESUMABLE_TOOLTIP
            addActionListener { actions.resume(item.session) }
        })
        add(JMenuItem("Open Transcript").apply {
            isEnabled = item.session.sourcePath != null
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK)
            addActionListener { actions.openTranscript(item.session) }
        })
        add(JMenuItem("Reveal in Files").apply {
            isEnabled = item.session.sourcePath != null
            addActionListener { actions.reveal(item.session) }
        })
        addSeparator()
        add(JMenuItem("Copy Session ID").apply {
            addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(item.session.id), null)
            }
        })
    }

    private inner class SessionRowRenderer : ListCellRenderer<SessionRowItem> {
        private val titleLabel = JBLabel().apply { putClientProperty("html.disable", true) }
        // Info lines wrap onto more lines instead of being cut with "..." (no hover needed to read them).
        private val detailLabel = WrappedRowText()
        private val statisticsLabel = WrappedRowText()
        private val resumeLink = JBLabel("Resume").apply { name = RESUME_LINK }
        private val transcriptLink = JBLabel("Transcript").apply { name = TRANSCRIPT_LINK }
        private val links = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(LINK_GAP), 0)).apply {
            isOpaque = false
            add(resumeLink)
            add(transcriptLink)
        }
        private val text = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            titleLabel.alignmentX = Component.LEFT_ALIGNMENT
            add(titleLabel)
            add(detailLabel)
            add(statisticsLabel)
        }
        private val content = JPanel(BorderLayout(JBUI.scale(AgentHubUiComponents.CONTROL_GAP), 0)).apply {
            isOpaque = false
            add(text, BorderLayout.CENTER)
            add(links, BorderLayout.EAST)
        }
        private val wrapper = RoundedSelectionPanel.wrap(content).apply {
            selectionArc = JBUI.scale(AgentHubUiComponents.SELECTION_ARC)
            selectionInsets = AgentHubUiComponents.listSelectionInsets()
        }
        private val boldFont = titleLabel.font.deriveFont(Font.BOLD)
        private val plainFont = titleLabel.font.deriveFont(Font.PLAIN)

        override fun getListCellRendererComponent(
            list: JList<out SessionRowItem>,
            value: SessionRowItem,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val colors = AgentHubUiComponents.rowTextColors(list.foreground, isSelected)
            when (value) {
                is SessionRowItem.Group -> {
                    statisticsLabel.isVisible = false
                    titleLabel.text = value.title
                    titleLabel.toolTipText = null
                    titleLabel.icon = value.agentId?.let(AgentHubUiComponents::faviconFor)
                    titleLabel.font = boldFont
                    detailLabel.text = value.detail
                    detailLabel.isVisible = true
                    links.isVisible = false
                    content.border = AgentHubUiComponents.listRowBorder()
                    wrapper.toolTipText = null
                    wrapper.accessibleContext.accessibleName =
                        "${value.title}, ${value.detail}, ${if (value.expanded) "expanded" else "collapsed"}"
                }
                is SessionRowItem.Session -> {
                    statisticsLabel.text = SessionDetailsModel.summary(value.session).orEmpty()
                    statisticsLabel.isVisible = statisticsLabel.text.isNotEmpty()
                    titleLabel.text = value.title
                    titleLabel.toolTipText = when {
                        value.resumable -> "Double-click to resume in ${agentName(value.session.agentId)}"
                        value.session.sourcePath != null -> "Double-click to open the transcript"
                        else -> null
                    }
                    titleLabel.icon = null
                    titleLabel.font = plainFont
                    detailLabel.text = value.detail.orEmpty()
                    detailLabel.isVisible = value.detail != null
                    links.isVisible = true
                    val linkColor = if (isSelected) colors.foreground else JBUI.CurrentTheme.Link.Foreground.ENABLED
                    resumeLink.foreground = if (value.resumable) linkColor else JBColor.GRAY
                    resumeLink.toolTipText = if (value.resumable) {
                        "Reopen this session with ${agentName(value.session.agentId)} (Ctrl+Enter)"
                    } else {
                        value.resumeBlockedReason ?: NOT_RESUMABLE_TOOLTIP
                    }
                    transcriptLink.foreground = if (value.session.sourcePath != null) linkColor else JBColor.GRAY
                    transcriptLink.toolTipText = value.session.sourcePath?.let { "Open $it (Shift+Enter)" } ?: "No transcript file recorded"
                    content.border = JBUI.Borders.empty(
                        AgentHubUiComponents.ROW_TEXT_PADDING,
                        AgentHubUiComponents.ROW_TEXT_PADDING + SESSION_INDENT,
                        AgentHubUiComponents.ROW_TEXT_PADDING,
                        AgentHubUiComponents.ROW_TEXT_PADDING,
                    )
                    wrapper.accessibleContext.accessibleName =
                        "${value.title}, ${value.detail ?: value.timeLabel}. Ctrl+Enter resumes when available; Shift+Enter opens transcript."
                }
            }
            wrapper.background = list.background
            wrapper.selectionColor = AgentHubUiComponents.rowHighlight(isSelected, hover.isHovered(index))
            titleLabel.foreground = colors.foreground
            detailLabel.foreground = if (isSelected) colors.secondaryForeground else JBColor.GRAY
            statisticsLabel.foreground = detailLabel.foreground
            WrappedRowText.prepareRow(list, wrapper, content, listOf(detailLabel, statisticsLabel))
            if (links.isVisible) {
                // The Resume/Transcript links sit to the right of the text column.
                val reserved = links.preferredSize.width + JBUI.scale(AgentHubUiComponents.CONTROL_GAP)
                listOf(detailLabel, statisticsLabel).forEach { it.wrapWidth = maxOf(40, it.wrapWidth - reserved) }
            }
            return wrapper
        }
    }

    private companion object {
        const val SESSION_INDENT = 16
        const val LINK_GAP = 12
        const val RESUME_LINK = "session-resume"
        const val TRANSCRIPT_LINK = "session-transcript"
        val LINK_NAMES = setOf(RESUME_LINK, TRANSCRIPT_LINK)
        const val NOT_RESUMABLE_TOOLTIP = "AgentHub does not know a native resume command for this agent yet"
    }
}
