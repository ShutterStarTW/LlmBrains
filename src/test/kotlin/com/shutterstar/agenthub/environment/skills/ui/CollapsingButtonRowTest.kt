package com.shutterstar.agenthub.environment.skills.ui

import com.intellij.icons.AllIcons
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JButton
import javax.swing.SwingUtilities

class CollapsingButtonRowTest {
    private fun row(): Triple<CollapsingButtonRow, JButton, JButton> {
        val open = JButton("Open SKILL.md")
        val promote = JButton("Promote to Shared")
        val row = CollapsingButtonRow.of(4) {
            addCollapsible(open, AllIcons.Actions.EditSource)
            add(promote)
        }
        return Triple(row, open, promote)
    }

    @Test fun `narrow row collapses only the secondary buttons and restores them when widened`() {
        SwingUtilities.invokeAndWait {
            val (row, open, promote) = row()
            val full = row.fullWidth()
            row.setSize(full - 10, 30)
            row.doLayout()
            assertTrue(row.collapsed)
            assertEquals("", open.text)
            assertTrue(open.icon != null)
            assertEquals("Open SKILL.md", open.accessibleContext.accessibleName)
            assertEquals("Promote to Shared", promote.text)

            row.setSize(full + 10, 30)
            row.doLayout()
            assertFalse(row.collapsed)
            assertEquals("Open SKILL.md", open.text)
        }
    }
}

class WrapLayoutTest {
}
