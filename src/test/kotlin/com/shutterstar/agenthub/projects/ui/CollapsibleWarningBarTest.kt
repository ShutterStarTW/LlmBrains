package com.shutterstar.agenthub.projects.ui

import com.shutterstar.agenthub.descendants
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.Container
import javax.swing.SwingUtilities

class CollapsibleWarningBarTest {
    @Test fun `the bar is hidden until there is something to warn about`() {
        SwingUtilities.invokeAndWait {
            val bar = CollapsibleWarningBar()
            assertFalse(bar.isVisible)

            bar.setWarnings(listOf("one"))
            assertTrue(bar.isVisible)
            assertEquals("1 discovery warning", bar.toggleText)

            bar.setWarnings(listOf("one", "two"))
            assertEquals("2 discovery warnings", bar.toggleText)

            bar.setWarnings(emptyList())
            assertFalse(bar.isVisible)
        }
    }

    @Test fun `expanding shows the warning texts and the choice survives new warnings and an empty spell`() {
        SwingUtilities.invokeAndWait {
            val bar = CollapsibleWarningBar()
            bar.setWarnings(listOf("first"))
            assertFalse(bar.isExpanded)

            bar.setExpanded(true)
            assertTrue(bar.isExpanded)
            assertTrue(labelTexts(bar).contains("first"))

            // Switching entity: new warnings arrive, the bar stays open...
            bar.setWarnings(listOf("second"))
            assertTrue(bar.isExpanded)
            assertTrue(labelTexts(bar).contains("second"))
            assertFalse(labelTexts(bar).contains("first"))

            // ...even across an entity that has none.
            bar.setWarnings(emptyList())
            bar.setWarnings(listOf("third"))
            assertTrue(bar.isExpanded)
        }
    }

    @Test fun `an overlong warning list is capped but its full diagnostics remain available`() {
        SwingUtilities.invokeAndWait {
            val bar = CollapsibleWarningBar(maxShown = 2)
            bar.setWarnings(listOf("a", "b", "c", "d"))
            bar.setExpanded(true)

            val texts = labelTexts(bar)
            assertTrue(texts.containsAll(listOf("a", "b")))
            assertFalse(texts.contains("c"))
            assertEquals("a\n\nb\n\nc\n\nd", bar.diagnosticsText())
            assertEquals("4 discovery warnings", bar.toggleText)
        }
    }

    private fun labelTexts(root: Container): List<String> = descendants(root).filterIsInstance<javax.swing.JTextArea>().map { it.text }

    companion object {
        @JvmStatic @BeforeAll fun setupScaling() { JBUIScale.setSystemScaleFactor(1.0f) }
    }
}
