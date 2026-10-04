package com.shutterstar.agenthub.projects.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.scale.JBUIScale
import com.shutterstar.agenthub.CodingAgent
import com.shutterstar.agenthub.environment.discovery.ProjectEnvironmentDiscoveryService
import com.shutterstar.agenthub.environment.ui.EnvironmentPanel
import com.shutterstar.agenthub.projects.ide.JetBrainsIdeInstallation
import com.shutterstar.agenthub.projects.ide.JetBrainsIdeProduct
import com.shutterstar.agenthub.projects.model.AgentProject
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import com.shutterstar.agenthub.projects.resolve.ProjectStats
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.nio.file.Path
import javax.swing.JComboBox
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class ProjectDetailsStateTest {
    @Test fun `should retain launch choices across equal and changed project snapshots and IDE detection`() = onEdt {
        val panel = ProjectDetailsPanel(project(), execute = {}, deliver = { it() })
        val firstIde = ide("first")
        val secondIde = ide("second")
        applyIdes(panel, listOf(firstIde, secondIde))
        val selected = discovered()
        panel.setProject(selected)
        val agentCombo = field<JComboBox<CodingAgent>>(panel, "agentCombo")
        val ideCombo = field<JComboBox<JetBrainsIdeInstallation>>(panel, "ideCombo")
        agentCombo.selectedIndex = 1
        val agentId = (agentCombo.selectedItem as CodingAgent).id
        ideCombo.selectedItem = secondIde

        repeat(10) { panel.setProject(selected) }
        panel.setProject(selected.copy(name = "Renamed"))
        assertEquals(agentId, (agentCombo.selectedItem as CodingAgent).id)
        assertEquals(secondIde.home, (ideCombo.selectedItem as JetBrainsIdeInstallation).home)
        applyIdes(panel, listOf(firstIde.copy(name = "New first"), secondIde.copy(name = "New second")))
        assertEquals(secondIde.home, (ideCombo.selectedItem as JetBrainsIdeInstallation).home)

        panel.setProject(selected.copy(agents = selected.agents.take(1)))
        assertEquals("claude", (agentCombo.selectedItem as CodingAgent).id)
        applyIdes(panel, listOf(firstIde))
        assertEquals(firstIde, ideCombo.selectedItem)
        panel.dispose()
    }

    @Test fun `should deduplicate in flight stats cache completed results and refresh after invalidation`() = onEdt {
        val pending = mutableListOf<() -> Unit>()
        var collections = 0
        val discovery = ProjectEnvironmentDiscoveryService(persist = { _, _ -> })
        val panel = ProjectDetailsPanel(
            project(), environmentDiscovery = discovery,
            collectStats = { _, _ -> collections++; ProjectStats(7, false, 10, null) },
            execute = { pending.add(it) }, deliver = { it() },
        )
        pending.clear() // Initial IDE detection is independent of the project requests.
        val selected = discovered()
        panel.setProject(selected)
        assertEquals(2, pending.size) // Statistics and Environment.
        repeat(10) { panel.setProject(selected.copy()) }
        assertEquals(2, pending.size)
        pending.removeAt(0).invoke()
        assertEquals(1, collections)
        assertTrue(field<JTextArea>(panel, "statsLabel").text.contains("7 files"))
        repeat(10) { panel.setProject(selected) }
        assertEquals(1, pending.size)
        panel.setProject(null)
        panel.setProject(selected)
        assertEquals(1, collections)

        discovery.invalidateAll()
        pending.clear()
        panel.setProject(selected)
        assertEquals(2, pending.size)
        pending.removeAt(0).invoke()
        assertEquals(2, collections)
        panel.dispose()
    }

    @Test fun `should discard stats delivered after invalidation selection change or disposal`() = onEdt {
        val pending = mutableListOf<() -> Unit>()
        val deliveries = mutableListOf<() -> Unit>()
        val discovery = ProjectEnvironmentDiscoveryService(persist = { _, _ -> })
        val panel = ProjectDetailsPanel(
            project(), environmentDiscovery = discovery,
            collectStats = { _, _ -> ProjectStats(99, false, 10, null) },
            execute = { pending.add(it) }, deliver = { deliveries.add(it) },
        )
        pending.clear()
        panel.setProject(discovered())
        pending.removeAt(0).invoke()
        discovery.invalidateAll()
        deliveries.removeAt(0).invoke()
        assertEquals("Calculating size…", field<JTextArea>(panel, "statsLabel").text)
        pending.clear()
        panel.setProject(discovered())
        pending.removeAt(0).invoke()
        panel.setProject(null)
        deliveries.removeAt(0).invoke()
        assertEquals("", field<JTextArea>(panel, "statsLabel").text)
        panel.setProject(discovered())
        assertTrue(field<JTextArea>(panel, "statsLabel").text.contains("99 files"))
        discovery.invalidateAll()
        pending.clear()
        panel.setProject(discovered())
        pending.removeAt(0).invoke()
        panel.dispose()
        deliveries.removeAt(0).invoke()
        assertEquals("Calculating size…", field<JTextArea>(panel, "statsLabel").text)
    }

    @Test fun `should render cached agent environment immediately while scheduling a refresh`() = onEdt {
        val discovery = ProjectEnvironmentDiscoveryService(persist = { _, _ -> })
        val found = discovered()
        val instruction = com.shutterstar.agenthub.environment.instructions.model.InstructionSource(
            "fixture/AGENTS.md", com.shutterstar.agenthub.environment.instructions.model.InstructionScope.PROJECT,
            setOf("codex"), com.shutterstar.agenthub.environment.instructions.model.InstructionType.AGENTS_MD,
        )
        val snapshot = com.shutterstar.agenthub.environment.model.ProjectEnvironment(
            found.identity.id, setOf("codex"), emptyList(), emptyList(), listOf(instruction),
        )
        val agentDiscovery = com.shutterstar.agenthub.environment.discovery.AgentEnvironmentDiscoveryService(
            discovery, cachedEnvironments = { mapOf(found.identity.id to snapshot) },
        )
        val pending = mutableListOf<() -> Unit>()
        val panel = EnvironmentPanel(project(), discovery, agentDiscovery, execute = { pending.add(it) }, deliverResult = { it() })
        val scope = DetailsScope.ForAgent("codex", listOf(found))
        panel.setScope(scope)
        assertEquals(1, field<javax.swing.JTable>(panel, "comparisonTable").rowCount)
        assertFalse(field<JTextArea>(panel, "summaryLabel").text.contains("Discovering"))
        assertEquals(1, pending.size)
        panel.setScope(DetailsScope.ForAgent("claude", listOf(found)))
        assertEquals(0, field<javax.swing.JTable>(panel, "comparisonTable").rowCount)
        panel.setScope(scope)
        assertEquals(1, field<javax.swing.JTable>(panel, "comparisonTable").rowCount)
        assertEquals(3, pending.size)
        discovery.invalidateAll()
        panel.setScope(scope)
        assertEquals(4, pending.size)
        assertEquals(1, field<javax.swing.JTable>(panel, "comparisonTable").rowCount)
        panel.dispose()
    }

    @Test fun `should preserve environment during filtering and request again after invalidation`() = onEdt {
        val discovery = ProjectEnvironmentDiscoveryService(persist = { _, _ -> })
        val pending = mutableListOf<() -> Unit>()
        val panel = EnvironmentPanel(project(), discovery, execute = { pending.add(it) }, deliverResult = { it() })
        val scope = DetailsScope.ForProject(discovered())
        panel.setScope(scope)
        repeat(10) { panel.setScope(scope.copy(project = scope.project.copy())) }
        assertEquals(1, pending.size)
        discovery.invalidateAll()
        panel.setScope(scope)
        assertEquals(2, pending.size)
        panel.dispose()
        panel.setScope(scope.copy(project = discovered("different")))
        assertEquals(2, pending.size)
    }

    @Test fun `should ignore non left environment activation and preserve selected row during filtering`() = onEdt {
        var opened = 0
        val panel = EnvironmentPanel(project(), openSkill = { _, _, _, _ -> opened++ }, execute = {})
        val scope = DetailsScope.ForProject(discovered())
        panel.setScope(scope)
        val comparison = com.shutterstar.agenthub.environment.ui.EnvironmentComparison(
            emptyList(), listOf(com.shutterstar.agenthub.environment.ui.ComparisonRow(
                "Skill", "test skill", setOf("codex"), skillId = "test-skill", sourcePath = "fixture/skill",
                skillScope = com.shutterstar.agenthub.environment.skills.model.SkillScope.GLOBAL,
            )),
        )
        panel.javaClass.getDeclaredField("lastComparison").apply { isAccessible = true }.set(panel, comparison)
        panel.javaClass.getDeclaredMethod("renderTable").apply { isAccessible = true }.invoke(panel)
        val table = field<javax.swing.JTable>(panel, "comparisonTable")
        table.setSize(900, 200)
        table.doLayout()
        table.setRowSelectionInterval(0, 0)
        repeat(10) { panel.setScope(scope.copy(project = scope.project.copy())) }
        assertEquals(0, table.selectedRow)
        assertEquals(1, table.rowCount)
        val rect = table.getCellRect(0, 0, false)
        for (button in listOf(java.awt.event.MouseEvent.BUTTON2, java.awt.event.MouseEvent.BUTTON3)) {
            val event = java.awt.event.MouseEvent(table, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, rect.x + 5, rect.y + 5, 2, false, button)
            table.mouseListeners.forEach { it.mouseClicked(event) }
        }
        assertEquals(0, opened)
        val event = java.awt.event.MouseEvent(table, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, rect.x + 5, rect.y + 5, 2, false, java.awt.event.MouseEvent.BUTTON1)
        table.mouseListeners.forEach { it.mouseClicked(event) }
        assertEquals(1, opened)
        panel.dispose()
    }

    @Test fun `should only open agent project rows on left double click`() = onEdt {
        var opened = 0
        val discovery = ProjectEnvironmentDiscoveryService(persist = { _, _ -> })
        val panel = AgentsPanel(
            project(), discovery, com.shutterstar.agenthub.environment.discovery.AgentEnvironmentDiscoveryService(discovery),
            openProject = { opened++ }, installationStatus = { "" },
        )
        val details = field<Any>(panel, "detailsPanel")
        val model = field<javax.swing.DefaultListModel<AgentProjectUsage>>(details, "projectModel")
        model.addElement(AgentProjectUsage("test", "Test", "fixture/test", null, sessionCount = 0, lastActivity = null))
        val list = field<javax.swing.JList<*>>(details, "projectList")
        list.setSize(900, 200)
        list.doLayout()
        val rect = list.getCellBounds(0, 0)
        for (button in listOf(java.awt.event.MouseEvent.BUTTON2, java.awt.event.MouseEvent.BUTTON3)) {
            val event = java.awt.event.MouseEvent(list, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, rect.x + 5, rect.y + 5, 2, false, button)
            list.mouseListeners.forEach { it.mouseClicked(event) }
        }
        assertEquals(0, opened)
        val event = java.awt.event.MouseEvent(list, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, rect.x + 5, rect.y + 5, 2, false, java.awt.event.MouseEvent.BUTTON1)
        list.mouseListeners.forEach { it.mouseClicked(event) }
        assertEquals(1, opened)
        panel.dispose()
    }

    private fun discovered(id: String = "test") = DiscoveredProject(
        ProjectIdentity(id, null, null, null), "Test", "fixture/$id", null, null, null,
        listOf("claude", "codex").map { AgentProject(it, id, 0, null, emptyList()) }, null,
    )

    private fun ide(name: String) = JetBrainsIdeInstallation(
        JetBrainsIdeProduct.INTELLIJ_IDEA, name, Path.of("fixture", name), Path.of("fixture", name, "idea.exe"), false,
    )

    private fun applyIdes(panel: ProjectDetailsPanel, installations: List<JetBrainsIdeInstallation>) {
        ProjectDetailsPanel::class.java.getDeclaredMethod("applyInstalledIdes", List::class.java)
            .apply { isAccessible = true }.invoke(panel, installations)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(panel: Any, name: String): T = panel.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(panel) as T

    private fun project(): Project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
        when (method.name) {
            "isDisposed" -> false
            "getName" -> "Test"
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
    } as Project

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

    companion object {
        @JvmStatic @BeforeAll fun scaling() { JBUIScale.setSystemScaleFactor(1.0f) }
    }
}
