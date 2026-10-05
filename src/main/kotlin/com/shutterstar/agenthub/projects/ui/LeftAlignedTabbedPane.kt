package com.shutterstar.agenthub.projects.ui

import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.event.ChangeListener

/**
 * Keeps nested tab labels and their content on the same leading edge as the surrounding panel.
 *
 * The platform tab UI reserves leading space both before the first tab title and around its
 * selected component. Those insets accumulate when tab panes are nested. The native tabbed pane
 * is therefore used only as a tab strip; page contents live in a separate full-width card panel.
 * This keeps the platform tab appearance and interaction without inheriting its content insets.
 */
internal class LeftAlignedTabbedPane : JPanel(BorderLayout()) {
    private val tabs = JBTabbedPane()
    private val cards = CardLayout()
    private val contentPanel = JPanel(cards)
    private val cardNames = mutableListOf<String>()
    private val tabStrip = TabStripPanel()
    // Tab strip on top, then an optional caller-supplied header (e.g. the shared search bar),
    // then the pages — so a control that applies to every tab can sit *below* the tab titles
    // and *above* the page content, on the same leading edge as both.
    private val north = JPanel(BorderLayout()).apply { isOpaque = false }
    private var header: Component? = null

    init {
        tabs.addChangeListener {
            cardNames.getOrNull(tabs.selectedIndex)?.let { cards.show(contentPanel, it) }
        }
        tabStrip.add(tabs)
        north.add(tabStrip, BorderLayout.NORTH)
        add(north, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }

    /** Places [component] between the tab strip and the pages; `null` removes the current header. */
    fun setHeader(component: Component?) {
        header?.let(north::remove)
        header = component
        component?.let { north.add(it, BorderLayout.CENTER) }
        revalidate()
        repaint()
    }

    /** Selects the first tab, if any — used to reset nested detail tabs when the selected entity changes. */
    fun selectFirst() {
        if (tabs.tabCount > 0) tabs.selectedIndex = 0
    }

    fun addTab(
        title: String,
        component: Component,
    ) {
        addTabInternal(title, component)
        revalidate()
        repaint()
    }

    fun selectedTitle(): String? = tabs.selectedIndex.takeIf { it >= 0 }?.let(tabs::getTitleAt)


    fun select(title: String) {
        (0 until tabs.tabCount).firstOrNull { tabs.getTitleAt(it) == title }?.let { tabs.selectedIndex = it }
    }

    /** Greys a tab out (or back in) without adding or removing it, so the tab strip's size never changes. */
    fun setTabEnabled(title: String, enabled: Boolean, disabledTooltip: String? = null) {
        val index = (0 until tabs.tabCount).firstOrNull { tabs.getTitleAt(it) == title } ?: return
        tabs.setEnabledAt(index, enabled)
        tabs.setToolTipTextAt(index, if (enabled) null else disabledTooltip)
    }

    fun addSelectionListener(listener: () -> Unit) {
        tabs.addChangeListener(ChangeListener { listener() })
    }

    fun setTabs(items: List<Pair<String, Component>>) {
        val selectedTitle = tabs.selectedIndex
            .takeIf { it >= 0 }
            ?.let(tabs::getTitleAt)
        tabs.removeAll()
        contentPanel.removeAll()
        cardNames.clear()
        items.forEach { (title, component) -> addTabInternal(title, component) }
        val restoredIndex = items.indexOfFirst { it.first == selectedTitle }
        if (restoredIndex >= 0) tabs.selectedIndex = restoredIndex
        revalidate()
        repaint()
    }

    private fun addTabInternal(
        title: String,
        component: Component,
    ) {
        val cardName = "tab-${cardNames.size}"
        cardNames.add(cardName)
        tabs.addTab(title, emptyTabPage())
        contentPanel.add(component, cardName)
        if (cardNames.size == 1) cards.show(contentPanel, cardName)
    }

    private fun leadingTabOffset(): Int {
        if (tabs.tabCount == 0) return 0
        val tabBounds = tabs.getBoundsAt(0) ?: return 0
        return tabBounds.x.coerceAtLeast(0)
    }

    private fun tabStripHeight(): Int {
        if (tabs.tabCount == 0) return 0
        val preferred = tabs.preferredSize
        // BorderLayout sizes its NORTH child to the full width *before* asking for its preferred
        // height — but that child is now the [north] wrapper, not [tabStrip] itself, whose width
        // is still stale (0 on first layout) at that point. Probing at a too-narrow width wraps
        // the tab titles onto a second row and reports a strip twice as tall as it will really be,
        // leaving a blank band under the tabs; the pane's own width is the real budget.
        val probeWidth = maxOf(tabStrip.width, width, preferred.width, 1)
        val probeHeight = maxOf(preferred.height, tabs.getFontMetrics(tabs.font).height, 1)
        tabs.setBounds(0, 0, probeWidth, probeHeight)
        tabs.doLayout()
        return (0 until tabs.tabCount).maxOf { index ->
            val bounds = tabs.getBoundsAt(index)
            bounds.y + bounds.height
        }
    }

    private fun emptyTabPage(): JPanel = JPanel(null).apply {
        preferredSize = Dimension(0, 0)
        minimumSize = Dimension(0, 0)
        maximumSize = Dimension(0, 0)
        isOpaque = false
    }

    private inner class TabStripPanel : JPanel(null) {
        init {
            isOpaque = false
        }

        override fun getPreferredSize(): Dimension = Dimension(tabs.preferredSize.width, tabStripHeight())

        override fun getMinimumSize(): Dimension = Dimension(0, tabStripHeight())

        override fun doLayout() {
            if (width <= 0 || height <= 0) return

            // Lay out once to obtain the current Look&Feel's real scaled tab bounds, then move
            // only the tab strip so the complete first tab button starts at the leading edge.
            tabs.setBounds(0, 0, width, height)
            tabs.doLayout()
            val leadingOffset = leadingTabOffset()
            tabs.setBounds(-leadingOffset, 0, width + leadingOffset, height)
            tabs.doLayout()
        }
    }
}
