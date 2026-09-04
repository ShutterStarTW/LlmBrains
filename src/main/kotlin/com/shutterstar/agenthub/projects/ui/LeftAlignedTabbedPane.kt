package com.shutterstar.agenthub.projects.ui

import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.JPanel

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

    init {
        tabs.addChangeListener {
            cardNames.getOrNull(tabs.selectedIndex)?.let { cards.show(contentPanel, it) }
        }
        tabStrip.add(tabs)
        add(tabStrip, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }

    fun addTab(
        title: String,
        component: Component,
    ) {
        addTabInternal(title, component)
        revalidate()
        repaint()
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
        val probeWidth = maxOf(tabStrip.width, preferred.width, 1)
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
