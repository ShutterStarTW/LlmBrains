package com.shutterstar.agenthub.projects.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.CodingAgents
import com.shutterstar.agenthub.FaviconLoader
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractButton
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.Border

internal object AgentHubUiComponents {
    const val PANEL_INSET = 8
    const val LIST_LEFT_INSET = 8
    const val TEXT_LEFT_INSET = LIST_LEFT_INSET + 8
    const val CONTROL_GAP = 6
    const val SMALL_GAP = 4
    const val DETAIL_DIVIDER_GAP = 10
    const val SETTINGS_SECTION_GAP = 16
    const val SETTINGS_TABLE_WIDTH = 730

    /** Corner radius for the rounded selection/hover pill behind a row, matching a selected tab. */
    const val SELECTION_ARC = 8

    /**
     * Horizontal margin between the rounded pill's outer edge and the row/list bounds. Zero, so
     * the pill spans the full row width — the breathing room around the text comes from
     * [ROW_TEXT_PADDING] instead, which sits *inside* the pill.
     */
    const val SELECTION_HORIZONTAL_INSET = 0

    /** Vertical margin around the rounded pill — this is what creates the gap between rows. */
    const val SELECTION_VERTICAL_INSET = 2

    /** Padding between the pill's edge and the row's text — same on all four sides. */
    const val ROW_TEXT_PADDING = 8

    /** A thin horizontal rule between stacked detail blocks — the same divider used between Agents-tab entries. */
    fun detailDivider(dotted: Boolean = false): JComponent = JPanel(BorderLayout()).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        isOpaque = false
        border = JBUI.Borders.empty(DETAIL_DIVIDER_GAP, 0)
        val separator = if (dotted) object : JSeparator() {
            override fun paintComponent(graphics: java.awt.Graphics) {
                // Reuse the native separator painting so dots share the theme's actual line
                // color, then reduce their opacity to keep them below section dividers.
                val paint = graphics.create() as java.awt.Graphics2D
                try {
                    val dots = java.awt.geom.Area()
                    val dotSize = JBUI.scale(1)
                    val spacing = JBUI.scale(3)
                    for (x in 0 until width step spacing) {
                        dots.add(java.awt.geom.Area(java.awt.Rectangle(x, 0, dotSize, height)))
                    }
                    paint.clip(dots)
                    paint.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.85f)
                    super.paintComponent(paint)
                } finally { paint.dispose() }
            }
        } else JSeparator()
        add(separator, BorderLayout.CENTER)
        // BorderLayout.maximumLayoutSize() always reports (MAX_VALUE, MAX_VALUE) regardless of
        // content, so inside a BoxLayout.Y_AXIS column this panel would otherwise swallow all
        // leftover vertical space instead of the thin rule it's meant to be.
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    fun alignedBorderlessScrollPane(component: Component): JComponent {
        val scrollPane = JBScrollPane(component).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(LIST_LEFT_INSET)
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    /**
     * Returns the scroll pane that holds [component] to its top. Done after the pending model/layout
     * update, otherwise the old viewport offset survives a change of entity and a tab that was not
     * visible at the time reopens scrolled down.
     */
    fun scrollToTop(component: Component) {
        SwingUtilities.invokeLater {
            (SwingUtilities.getAncestorOfClass(JScrollPane::class.java, component) as? JScrollPane)
                ?.viewport?.viewPosition = Point(0, 0)
        }
    }

    /** Shared filter feedback row; wrapped text remains centered beside the reset button. */
    fun filterStatusRow(summary: JTextArea, clearButton: AbstractButton): JPanel = JPanel(BorderLayout()).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, TEXT_LEFT_INSET, CONTROL_GAP, 0)
        add(JPanel(GridBagLayout()).apply {
            isOpaque = false
            add(summary, GridBagConstraints().apply {
                weightx = 1.0
                weighty = 1.0
                fill = GridBagConstraints.HORIZONTAL
            })
        }, BorderLayout.CENTER)
        add(clearButton, BorderLayout.EAST)
        isVisible = false
    }
    /** A status line whose preferred height follows the available tool-window width. */
    fun wrappingStatusText(): JTextArea = WidthAwareTextArea().apply {
        alignmentX = Component.LEFT_ALIGNMENT
        border = null
        margin = Insets(0, 0, 0, 0)
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        font = UIManager.getFont("Label.font")
        foreground = JBColor.foreground()
    }

    /** A file/path label that stays on one line and exposes its full value on hover. */
    fun singleLineText(value: String, maxPreferredWidth: Int = 520): JBLabel =
        object : JBLabel(value) {
            override fun getPreferredSize(): Dimension = super.getPreferredSize().apply {
                width = minOf(width, JBUI.scale(maxPreferredWidth))
            }

            override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            putClientProperty("html.disable", true)
            toolTipText = value
            alignmentX = Component.LEFT_ALIGNMENT
        }

    /** The standard inset for a two-line "title + gray detail" list row renderer. */
    fun listRowBorder(): Border = JBUI.Borders.empty(ROW_TEXT_PADDING)

    /** Margin for the selection/hover pill wrapping a whole list row (all four sides inset). */
    fun listSelectionInsets(): Insets =
        JBUI.insets(SELECTION_VERTICAL_INSET, SELECTION_HORIZONTAL_INSET, SELECTION_VERTICAL_INSET, SELECTION_HORIZONTAL_INSET)

    data class RowTextColors(
        val foreground: Color,
        val secondaryForeground: Color,
    )

    /** Foreground/secondary-foreground for a row renderer, selected or not. */
    fun rowTextColors(foreground: Color, isSelected: Boolean): RowTextColors = RowTextColors(
        foreground = if (isSelected) selectionTextColor() else foreground,
        secondaryForeground = if (isSelected) selectionTextColor() else JBColor.GRAY,
    )

    /**
     * Fill color for the rounded pill painted behind a row — selected rows get the theme's
     * selection color, hovered-but-unselected rows get the same gray the platform uses for
     * tab/list hover, and idle rows get no fill at all.
     */
    fun rowHighlight(isSelected: Boolean, isHovered: Boolean): Color? = when {
        isSelected -> selectionFillColor()
        isHovered -> JBUI.CurrentTheme.List.Hover.background(true)
        else -> null
    }

    private fun selectionFillColor(): Color = JBUI.CurrentTheme.List.Selection.background(true)

    private fun selectionTextColor(): Color = JBUI.CurrentTheme.List.Selection.foreground(true)

    fun displayName(agentId: String): String =
        CodingAgents.byId(agentId)?.name ?: agentId

    fun faviconFor(agentId: String): Icon? =
        CodingAgents.byId(agentId)?.let(FaviconLoader::get)

    /** One agent choice: selection control, then favicon, then name. The name also toggles the control. */
    fun agentChoiceRow(button: AbstractButton, name: String, icon: Icon?): JPanel {
        button.text = ""
        button.icon = null
        button.accessibleContext.accessibleName = name
        val label = JLabel(name, icon, JLabel.LEADING).apply {
            labelFor = button
            iconTextGap = JBUI.scale(SMALL_GAP)
            isEnabled = button.isEnabled
            toolTipText = button.toolTipText
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(event) && button.isEnabled) button.doClick()
                }
            })
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(SMALL_GAP), 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
            add(button)
            add(label)
        }
    }

    /**
     * Marks a skill row as the shared/canonical source - the one occurrence that otherwise shows
     * no agent icon at all (its [com.shutterstar.agenthub.environment.skills.model.SkillSource.agentId]
     * is null), which without this reads as an unexplained blank row next to every agent-specific
     * occurrence's favicon.
     */
    val sharedSkillIcon: Icon by lazy { IconLoader.getIcon("/icons/shared-skill.svg", AgentHubUiComponents::class.java) }


    /** A filter choice uses the same leading inset as agentChoiceRow. */
    fun filterChoiceRow(button: AbstractButton): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(SMALL_GAP), 0)).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        isOpaque = false
        add(button)
    }

    fun filterGroupHeader(text: String, first: Boolean): JBLabel = sectionHeader(text).apply {
        border = JBUI.Borders.empty(if (first) 0 else PANEL_INSET, 0, SMALL_GAP, 0)
    }

    /**
     * A vertical [BoxLayout] column. [opaque], [leftAligned] and [border] cover the variations the panels
     * need; anything else is set by the caller on the returned panel.
     */
    fun verticalBox(opaque: Boolean = true, leftAligned: Boolean = false, border: Border? = null): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = opaque
        if (leftAligned) alignmentX = Component.LEFT_ALIGNMENT
        if (border != null) this.border = border
    }

    /** A nested detail block: the parent owns the outer padding and the divider owns the vertical gap. */
    fun detailBlock(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty()
    }

    /**
     * A vertical `BoxLayout` panel with the standard [PANEL_INSET] padding — the content root used
     * by almost every dialog in `environment/skills/ui/SkillSyncDialogs.kt`.
     */
    fun verticalBoxPanel(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(PANEL_INSET)
    }

    /**
     * A directory or file path inside a plan/preview: one line, always the same darker gray, so every
     * plan dialog (share, undo, bulk) reads the same. The full value is the hover.
     */
    fun pathText(value: String): JBLabel = singleLineText(value).apply { foreground = JBColor.GRAY }

    /**
     * Scrolls [content] vertically only: the content is laid out at the viewport's width (no horizontal
     * scrollbar), so long text wraps and long paths are cut by their own ellipsis instead.
     */
    fun verticalScrollPane(content: JComponent, width: Int, height: Int): JBScrollPane {
        val tracking = object : JPanel(BorderLayout()), javax.swing.Scrollable {
            override fun getPreferredScrollableViewportSize(): java.awt.Dimension = preferredSize
            override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
            override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int =
                maxOf(JBUI.scale(16), visibleRect.height - JBUI.scale(16))
            override fun getScrollableTracksViewportWidth(): Boolean = true
            override fun getScrollableTracksViewportHeight(): Boolean = false
        }.apply { add(content, BorderLayout.NORTH) }
        return JBScrollPane(tracking).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = java.awt.Dimension(JBUI.scale(width), JBUI.scale(height))
        }
    }

    /** A bold, left-aligned label used as a section title inside a [verticalBoxPanel]. */
    fun sectionHeader(text: String): JBLabel = JBLabel(text).apply {
        font = font.deriveFont(Font.BOLD)
        alignmentX = JComponent.LEFT_ALIGNMENT
    }
}
