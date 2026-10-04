package com.shutterstar.agenthub.projects.ui

import com.intellij.ui.scale.JBUIScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Graphics
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JRadioButton
import javax.swing.SwingUtilities

class AgentHubUiComponentsTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun setUpScaling() {
            // Outside a running IDE, JBUIScale's lazy system scale factor throws ("Must be
            // precomputed") on the first JBUI.scale call; precompute it once for this JVM.
            JBUIScale.setSystemScaleFactor(1.0f)
        }
    }

    private val icon = object : Icon {
        override fun getIconWidth() = 16
        override fun getIconHeight() = 16
        override fun paintIcon(component: Component?, graphics: Graphics?, x: Int, y: Int) = Unit
    }

    @Test fun `locked agent choice cannot be toggled by clicking its name`() {
        SwingUtilities.invokeAndWait {
            val radio = JRadioButton("Claude", true).apply { isEnabled = false }
            val row = AgentHubUiComponents.agentChoiceRow(radio, "Claude", icon)
            val label = row.getComponent(1) as JLabel

            assertFalse(label.isEnabled)
            label.dispatchEvent(MouseEvent(label, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 1, 1, 1, false, MouseEvent.BUTTON1))
            assertTrue(radio.isSelected)
        }
    }
}
