package com.shutterstar.agenthub.projects.ui

import com.shutterstar.agenthub.descendants
import com.intellij.openapi.project.Project
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.shutterstar.agenthub.environment.skills.ui.SkillsPanel
import com.shutterstar.agenthub.environment.ui.ComparisonAgent
import com.shutterstar.agenthub.environment.ui.ComparisonRow
import com.shutterstar.agenthub.environment.ui.EnvironmentComparison
import com.shutterstar.agenthub.environment.ui.EnvironmentPanel
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class UiSpacingAndDetailsRegressionTest {
    @Test fun `should show session measurements on first selection and reset details scroll when switching`() = onEdt {
        val first = com.shutterstar.agenthub.projects.model.AgentSession("s1", "claude", "/p", null, null, "/s1",
            title = "First title", statistics = mapOf("models" to "claude-test", "totalTokens" to "123"))
        val second = first.copy(id = "s2", title = "Second title", statistics = emptyMap())
        val relation = com.shutterstar.agenthub.projects.model.AgentProject("claude", "p", 2, null, listOf(first, second))
        val discovered = DiscoveredProject(ProjectIdentity("p", "/p", null, null), "Project", "/p", null, null, null, listOf(relation), null)
        val panel = SessionsPanel(agentName = { it })
        panel.setScope(DetailsScope.ForProject(discovered))
        val host = object : JPanel(java.awt.BorderLayout()) { override fun isValidateRoot(): Boolean = true }.apply { add(panel) }
        host.addNotify()
        try {
            host.setSize(500, 600)
            host.validate()
            val list = field<javax.swing.JList<SessionRowItem>>(panel, "list")
            val strip = field<DetailsStrip>(panel, "detailsScroll")
            assertFalse(strip.isVisible)
            list.selectedIndex = 1
            host.validate()
            assertTrue(strip.isVisible)
            assertTrue(strip.height > 0)
            assertTrue(strip.content.contains("Total tokens: 123"))
            val viewport = strip.viewport
            viewport.viewPosition = java.awt.Point(0, 40)
            list.selectedIndex = 2
            host.validate()
            assertEquals(0, viewport.viewPosition.y)
            assertTrue(strip.content.startsWith("Second title"))
            assertFalse(strip.content.contains("123"))
            list.selectedIndex = 0
            assertFalse(strip.isVisible)
        } finally { host.removeNotify() }
    }

    @Test fun `first environment selection lays out the info strip without a second click`() = onEdt {
        val panel = environment()
        val table = field<JTable>(panel, "comparisonTable")
        table.clearSelection()
        val host = object : JPanel(java.awt.BorderLayout()) {
            override fun isValidateRoot(): Boolean = true
        }.apply { add(panel) }
        host.addNotify()
        try {
            host.setSize(1000, 650)
            host.validate()
            val scroll = field<DetailsStrip>(panel, "detailScroll")
            assertFalse(scroll.isVisible)
            for (category in listOf("Config", "Instruction", "MCP", "Skill")) {
                val data = comparison().copy(rows = listOf(comparison().rows.single().copy(category = category)))
                panel.javaClass.getDeclaredField("lastComparison").apply { isAccessible = true }.set(panel, data)
                panel.javaClass.getDeclaredMethod("renderTable").apply { isAccessible = true }.invoke(panel)
                host.validate()
                table.changeSelection(0, 0, false, false)
                host.validate()
                assertTrue(scroll.isVisible)
                assertTrue(scroll.height > 0, "$category details should occupy space after the first selection")
                assertTrue(scroll.viewport.extentSize.height > 0)
                assertTrue(field<DetailsStrip>(panel, "detailScroll").content.isNotBlank())
                table.clearSelection()
                host.validate()
            }
        } finally { host.removeNotify(); panel.dispose() }
    }
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "needs a display; the CI runner is headless")
    @Test fun `real click on an environment row inside a tab shows the info strip immediately`() = onEdt {
        val panel = environment()
        val table = field<JTable>(panel, "comparisonTable")
        table.clearSelection()
        val tabs = LeftAlignedTabbedPane().apply {
            addTab("Overview", JPanel())
            addTab("Environment", panel)
        }
        val host = object : JPanel(java.awt.BorderLayout()) { override fun isValidateRoot(): Boolean = true }.apply { add(tabs) }
        host.addNotify()
        try {
            host.setSize(700, 650)
            host.validate()
            tabs.select("Environment")
            host.validate()
            layout(host)
            val strip = field<DetailsStrip>(panel, "detailScroll")
            val cell = table.getCellRect(0, 1, false)
            for (id in listOf(java.awt.event.MouseEvent.MOUSE_PRESSED, java.awt.event.MouseEvent.MOUSE_RELEASED, java.awt.event.MouseEvent.MOUSE_CLICKED)) {
                val event = java.awt.event.MouseEvent(table, id, System.currentTimeMillis(), java.awt.event.InputEvent.BUTTON1_DOWN_MASK, cell.x + 4, cell.y + 4, cell.x + 4, cell.y + 4, 1, false, java.awt.event.MouseEvent.BUTTON1)
                table.mouseListeners.forEach {
                    when (id) {
                        java.awt.event.MouseEvent.MOUSE_PRESSED -> it.mousePressed(event)
                        java.awt.event.MouseEvent.MOUSE_RELEASED -> it.mouseReleased(event)
                        else -> it.mouseClicked(event)
                    }
                }
            }
            host.validate()
            assertEquals(0, table.selectedRow)
            assertTrue(strip.isVisible)
            assertTrue(strip.height > 0, "strip height ${strip.height}, bounds ${strip.bounds}, panel ${panel.size}")
        } finally { host.removeNotify(); panel.dispose() }
    }

    @Test fun `refreshing the same environment scope keeps the clicked row selected`() = onEdt {
        val panel = environment()
        val table = field<JTable>(panel, "comparisonTable")
        val scope = panel.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(panel)
        assertEquals(0, table.selectedRow)
        // What a focus-triggered shared-data reload does: new generation, same entity.
        panel.javaClass.getDeclaredField("scopeGeneration").apply { isAccessible = true }.set(panel, -5L)
        panel.setScope(scope as DetailsScope)
        panel.javaClass.getDeclaredField("lastComparison").apply { isAccessible = true }.set(panel, comparison())
        panel.javaClass.getDeclaredMethod("renderTable").apply { isAccessible = true }.invoke(panel)
        assertEquals(0, table.selectedRow)
        assertTrue(field<DetailsStrip>(panel, "detailScroll").isVisible)
        panel.dispose()
    }

    @Test fun `vertical scroll pane never scrolls horizontally and lays content out at the viewport width`() = onEdt {
        val wide = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(object : JLabel("wide") { override fun getPreferredSize() = java.awt.Dimension(2000, 20) })
            repeat(60) { add(JLabel("row $it")) }
        }
        val pane = AgentHubUiComponents.verticalScrollPane(wide, 300, 200)
        pane.setSize(300, 200)
        pane.doLayout(); pane.viewport.doLayout()
        assertFalse(pane.horizontalScrollBar.isVisible)
        assertTrue(pane.verticalScrollBar.isVisible || pane.viewport.view.preferredSize.height > pane.viewport.height)
        assertEquals(pane.viewport.width, pane.viewport.view.width)
    }

    @Test fun `should wrap and expose complete selected metadata without depending on hover`() = onEdt {
        val panel = environment()
        val table = field<JTable>(panel, "comparisonTable")
        table.setRowSelectionInterval(0, 0)
        panel.setSize(280, 600)
        repeat(3) { layout(panel) }
        val scroll = field<DetailsStrip>(panel, "detailScroll")
        assertTrue(scroll.isVisible)
        assertTrue(scroll.preferredSize.height in 1..JBUI.scale(160))
        assertTrue(scroll.content.endsWith(comparison().rows.single().extraDetailLines.last()))
        assertTrue(scroll.content.contains("complete warning."))
        val narrowHeight = scroll.preferredSize.height
        panel.setSize(1000, 600)
        repeat(3) { layout(panel) }
        assertTrue(narrowHeight >= scroll.preferredSize.height)
        assertFalse(descendants(panel).filterIsInstance<JButton>().any { it.text.startsWith("Show details") })
        panel.dispose()
    }

    @Test fun `environment filters preserve visible selection and explain and reset zero matches`() = onEdt {
        val panel = environment()
        assertFalse(field<JPanel>(panel, "filterStatus").isVisible)
        val original = comparison()
        val rows = listOf(original.rows.single().copy(category = "Instruction", name = "AGENTS.md", sourcePath = "fixture/AGENTS.md"), original.rows.single())
        panel.javaClass.getDeclaredField("lastComparison").apply { isAccessible = true }.set(panel, original.copy(rows = rows))
        val render = panel.javaClass.getDeclaredMethod("renderTable").apply { isAccessible = true }
        render.invoke(panel)
        val table = field<JTable>(panel, "comparisonTable")
        table.setRowSelectionInterval(1, 1)
        val type = field<javax.swing.JComboBox<String>>(panel, "typeFilter")
        type.selectedItem = "Config"
        assertEquals(0, table.selectedRow)
        assertEquals("config.toml", table.getValueAt(table.selectedRow, 0))
        type.selectedItem = "MCP"
        assertEquals(0, table.rowCount)
        assertTrue(field<JTextArea>(panel, "filterSummary").text.contains("0 of 2 items"))
        val clear = field<JButton>(panel, "clearFiltersButton")
        assertTrue(clear.isVisible)
        assertTrue(field<JPanel>(panel, "filterStatus").isVisible)
        clear.doClick()
        assertEquals(2, table.rowCount)
        assertEquals("All", type.selectedItem)
        assertFalse(clear.isVisible)
        assertFalse(field<JPanel>(panel, "filterStatus").isVisible)
        assertTrue(table.actionMap.get(RowContextMenus.ACTION) != null)
        panel.dispose()
    }

    private fun environment(): EnvironmentPanel = EnvironmentPanel(project(), execute = {}).apply {
        val discovered = DiscoveredProject(ProjectIdentity("test", null, null, null), "Test", null, null, null, null, emptyList(), null)
        setScope(DetailsScope.ForProject(discovered))
        javaClass.getDeclaredField("lastComparison").apply { isAccessible = true }.set(this, comparison())
        javaClass.getDeclaredMethod("renderTable").apply { isAccessible = true }.invoke(this)
        field<JTable>(this, "comparisonTable").setRowSelectionInterval(0, 0)
    }

    private fun comparison() = EnvironmentComparison(
        (1..10).map { ComparisonAgent("agent-$it", "Agent number $it") },
        listOf(ComparisonRow(
            "Config", "config.toml", (1..10).mapTo(mutableSetOf()) { "agent-$it" }, scope = "Project",
            sourcePath = "fixture/deeply/nested/config.toml",
            detail = "Format: TOML · Size: 123 KB · Modified: September 30, 2026 at 23:59:59",
            extraDetailLines = listOf("Model: example-model", "A long explanation of unavailable configuration metadata that must remain readable in a narrow panel. This is the complete warning."),
        )),
    )

    private fun withScales(action: (Float) -> Unit) {
        val previous = JBUI.scale(1.0f)
        try { listOf(1.0f, 1.5f, 2.0f).forEach { JBUIScale.setUserScaleFactor(it); action(it) } }
        finally { JBUIScale.setUserScaleFactor(previous) }
    }

    private fun screenshot(panel: JPanel, width: Int, name: String, height: Int = 650) {
        val directory = System.getProperty("agenthub.ui.screenshot.dir") ?: return
        panel.setSize(width, height)
        repeat(3) { layout(panel) }
        descendants(panel).filterIsInstance<JTable>().forEach { table ->
            for (row in 0 until table.rowCount) for (column in 0 until table.columnCount) {
                val renderer = table.prepareRenderer(table.getCellRenderer(row, column), row, column)
                renderer.setBounds(table.getCellRect(row, column, false))
                if (renderer is Container) layout(renderer)
            }
        }
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try { panel.printAll(graphics) } finally { graphics.dispose() }
        val output = Path.of(directory)
        Files.createDirectories(output)
        ImageIO.write(image, "png", output.resolve("$name.png").toFile())
    }

    private fun layout(root: Container) { root.doLayout(); root.components.filterIsInstance<Container>().forEach(::layout) }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(value: Any, name: String): T = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value) as T
    private fun project(): Project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
        when (method.name) { "isDisposed" -> false; else -> when (method.returnType) { Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 0; else -> null } }
    } as Project
    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
    companion object { @JvmStatic @BeforeAll fun scaling() { JBUIScale.setSystemScaleFactor(1.0f) } }
}
