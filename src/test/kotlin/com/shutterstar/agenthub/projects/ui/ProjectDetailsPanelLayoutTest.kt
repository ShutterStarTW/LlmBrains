package com.shutterstar.agenthub.projects.ui

import com.shutterstar.agenthub.descendants
import com.intellij.openapi.project.Project
import com.intellij.ui.scale.JBUIScale
import com.shutterstar.agenthub.environment.ui.EnvironmentPanel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.Container
import java.lang.reflect.Proxy
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class ProjectDetailsPanelLayoutTest {
    @Test fun `git remote becomes a web link only when it is a hosted address`() {
        assertEquals("https://github.com/owner/repo", ProjectDetailsPanel.webUrlOf("github.com/owner/repo"))
        assertEquals(null, ProjectDetailsPanel.webUrlOf("c:/work/repo"))
        assertEquals(null, ProjectDetailsPanel.webUrlOf("/srv/git/repo"))
        assertEquals(null, ProjectDetailsPanel.webUrlOf("localhost/repo"))
    }

    private fun project(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "isDisposed" -> false
            "getName" -> "Test"
            "getBasePath" -> null
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
    } as Project

    private fun layout(root: Container) {
        root.doLayout()
        root.components.filterIsInstance<Container>().forEach(::layout)
    }

    companion object {
        @JvmStatic @BeforeAll fun setupScaling() {
            JBUIScale.setSystemScaleFactor(1.0f)
        }
    }
}
