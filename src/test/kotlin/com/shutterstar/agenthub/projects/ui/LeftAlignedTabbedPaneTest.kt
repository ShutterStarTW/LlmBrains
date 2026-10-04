package com.shutterstar.agenthub.projects.ui

import com.intellij.ui.scale.JBUIScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.Container
import java.awt.Point
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

class LeftAlignedTabbedPaneTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun setUpScaling() {
            // Outside a running IDE application, JBUIScale's lazy system-scale-factor getter
            // throws ("Must be precomputed") the first time any JBUI-based Swing component
            // (here, JBTabbedPane) is constructed. Precompute it once for this JVM before any
            // such component gets created.
            JBUIScale.setSystemScaleFactor(1.0f)
        }
    }

    @Test
    fun `selectFirst returns to the first tab`() {
        SwingUtilities.invokeAndWait {
            val alignedTabs = LeftAlignedTabbedPane().apply {
                addTab("Overview", JPanel())
                addTab("Sessions", JPanel())
                addTab("Environment", JPanel())
            }
            alignedTabs.select("Environment")
            assertEquals("Environment", alignedTabs.selectedTitle())
            alignedTabs.selectFirst()
            assertEquals("Overview", alignedTabs.selectedTitle())
            // Harmless on an empty pane.
            LeftAlignedTabbedPane().selectFirst()
        }
    }

    private fun tabButtonOrigin(
        alignedTabs: LeftAlignedTabbedPane,
        root: JPanel,
    ): Point {
        val tabs = findTabbedPane(alignedTabs)
        val bounds = tabs.getBoundsAt(0)
        return SwingUtilities.convertPoint(tabs, bounds.location, root)
    }

    private fun tabButtonBottom(
        alignedTabs: LeftAlignedTabbedPane,
        root: JPanel,
    ): Int {
        val tabs = findTabbedPane(alignedTabs)
        val bounds = tabs.getBoundsAt(0)
        return SwingUtilities.convertPoint(tabs, Point(bounds.x, bounds.y + bounds.height), root).y
    }

    private fun componentOrigin(
        component: Container,
        root: JPanel,
    ): Point = SwingUtilities.convertPoint(component, Point(0, 0), root)

    private fun findTabbedPane(container: Container): JTabbedPane {
        container.components.forEach { component ->
            if (component is JTabbedPane) return component
            if (component is Container) {
                runCatching { findTabbedPane(component) }.getOrNull()?.let { return it }
            }
        }
        error("No JTabbedPane found")
    }

    private fun layoutRecursively(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layoutRecursively)
    }
}
