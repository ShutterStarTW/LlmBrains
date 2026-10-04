package com.shutterstar.agenthub.projects.ui

import com.shutterstar.agenthub.projects.model.AgentProject
import com.shutterstar.agenthub.projects.model.AgentSession
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Point
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

class ScrollResetOnEntityChangeTest {
    private fun project(id: String, sessions: Int): DiscoveredProject {
        val list = (1..sessions).map { AgentSession("$id-s$it", "claude", "/$id", null, null, "/$id/s$it", title = "Session $it") }
        val relation = AgentProject("claude", id, sessions, null, list)
        return DiscoveredProject(ProjectIdentity(id, "/$id", null, null), id, "/$id", null, null, null, listOf(relation), null)
    }

    @Test
    fun `a different project opens the sessions list at the top`() {
        val first = project("a", 60)
        val other = project("b", 60)
        lateinit var scroll: JScrollPane
        lateinit var panel: SessionsPanel
        SwingUtilities.invokeAndWait {
            panel = SessionsPanel(agentName = { it })
            val host = object : JPanel(BorderLayout()) { override fun isValidateRoot(): Boolean = true }.apply { add(panel) }
            host.addNotify()
            host.setSize(500, 300)
            panel.setScope(DetailsScope.ForProject(first))
            host.validate()
            val list = panel.javaClass.getDeclaredField("list").apply { isAccessible = true }.get(panel) as java.awt.Component
            scroll = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, list) as JScrollPane
            scroll.viewport.viewPosition = Point(0, 120)
            assertTrue(scroll.viewport.viewPosition.y > 0)
        }
        SwingUtilities.invokeAndWait { panel.setScope(DetailsScope.ForProject(other)) }
        SwingUtilities.invokeAndWait {}
        assertEquals(0, scroll.viewport.viewPosition.y)
    }
}
