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
    fun `should keep nested tab content on the same leading edge`() {
        SwingUtilities.invokeAndWait {
            val content = JPanel()
            val innerTabs = LeftAlignedTabbedPane().apply {
                addTab("Environment", content)
                addTab("Projects", JPanel())
            }
            val outerTabs = LeftAlignedTabbedPane().apply {
                addTab("Agents", innerTabs)
                addTab("Projects", JPanel())
            }

            outerTabs.setSize(400, 300)
            layoutRecursively(outerTabs)

            val contentOrigin = SwingUtilities.convertPoint(content, Point(0, 0), outerTabs)
            assertEquals(0, tabButtonOrigin(outerTabs, outerTabs).x)
            assertEquals(0, tabButtonOrigin(innerTabs, outerTabs).x)
            assertEquals(tabButtonBottom(outerTabs, outerTabs), componentOrigin(innerTabs, outerTabs).y)
            assertEquals(0, contentOrigin.x)
            assertEquals(400, content.width)
        }
    }

    @Test
    fun `replacing visible tabs does not shrink the content area`() {
        SwingUtilities.invokeAndWait {
            val content = JPanel()
            val alignedTabs = LeftAlignedTabbedPane()
            repeat(5) { cycle ->
                val items = if (cycle % 2 == 0) {
                    listOf("Skills" to content)
                } else {
                    listOf("Skills" to content, "MCP" to JPanel(), "Warnings" to JPanel())
                }
                alignedTabs.setTabs(items)
                alignedTabs.setSize(400, 300)
                layoutRecursively(alignedTabs)

                assertEquals(items.size, findTabbedPane(alignedTabs).tabCount)
                assertEquals(0, componentOrigin(content, alignedTabs).x)
                assertEquals(400, content.width)
            }
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
