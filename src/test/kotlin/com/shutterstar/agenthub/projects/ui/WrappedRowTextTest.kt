package com.shutterstar.agenthub.projects.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities

class WrappedRowTextTest {
    private val path = "Path: K:\\IdeaProjects\\" + "very-long-nested-directory\\".repeat(6) + "project"

    @Test fun `long info line takes more lines when the list is narrow`() {
        SwingUtilities.invokeAndWait {
            val text = WrappedRowText().apply { this.text = path }

            text.wrapWidth = 600
            val wide = text.preferredSize.height
            text.wrapWidth = 160
            val narrow = text.preferredSize.height

            assertTrue(narrow > wide, "narrow=$narrow, wide=$wide")
            assertEquals(160, text.preferredSize.width)
        }
    }

}

class WrappedRowListTest {
    private class Renderer : javax.swing.ListCellRenderer<String> {
        private val info = WrappedRowText()
        private val content = javax.swing.JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            border = AgentHubUiComponents.listRowBorder()
            add(info)
        }
        private val wrapper = RoundedSelectionPanel.wrap(content).apply {
            selectionInsets = AgentHubUiComponents.listSelectionInsets()
        }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out String>,
            value: String,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            info.text = value
            WrappedRowText.prepareRow(list, wrapper, content, listOf(info))
            return wrapper
        }
    }

}
