package com.shutterstar.agenthub.projects.ui

import com.intellij.ui.scale.JBUIScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

class LayoutMeasurementRegressionTest {
    @Test fun `preferred minimum and maximum measurements never resize a mounted text component`() = SwingUtilities.invokeAndWait {
        val root = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val text = AgentHubUiComponents.wrappingStatusText().apply { text = "Long metadata that wraps across the available width. ".repeat(8) }
        root.add(text)
        root.addNotify()
        try {
            for (width in listOf(1000, 280, 600, 240, 1000)) {
                root.setSize(width, 500)
                root.validate()
                val bounds = text.bounds
                repeat(3) {
                    (root.layout as BoxLayout).invalidateLayout(root)
                    assertTrue(root.preferredSize.height > 0)
                    text.preferredSize
                    text.minimumSize
                    text.maximumSize
                    assertEquals(bounds, text.bounds, "A size query must not invalidate the parent's BoxLayout mid-calculation")
                }
            }
        } finally { root.removeNotify() }
    }

    companion object { @JvmStatic @BeforeAll fun scaling() { JBUIScale.setSystemScaleFactor(1.0f) } }
}