package com.shutterstar.agenthub.projects.ui

import com.shutterstar.agenthub.descendants
import com.intellij.ui.JBSplitter
import com.intellij.ui.scale.JBUIScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.Container
import java.awt.event.ComponentEvent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ResponsiveMasterDetailTest {
    @Test fun `wide layout shows list and details side by side through a themed JBSplitter, not a raw JSplitPane`() {
        SwingUtilities.invokeAndWait {
            val list = JLabel("list")
            val details = JLabel("details")
            val responsive = ResponsiveMasterDetail(list, details, "Back", hasSelection = { true })
            val host = host(responsive.component, 900)

            assertFalse(responsive.isCompact)
            val splitter = descendants(responsive.component).filterIsInstance<JBSplitter>().single()
            assertTrue(descendants(splitter).contains(list))
            assertTrue(descendants(splitter).contains(details))
            host.removeAll()
        }
    }

    @Test fun `resizing within one layout does not reattach the component tree`() {
        SwingUtilities.invokeAndWait {
            var notifications = 0
            val list = object : JPanel() {
                override fun addNotify() {
                    notifications++
                    super.addNotify()
                }
            }
            val responsive = ResponsiveMasterDetail(list, JLabel("details"), "Back", hasSelection = { true })
            val host = host(responsive.component, 900)
            val afterMount = notifications

            resize(host, responsive.component, 950)
            responsive.selectionChanged()

            assertEquals(afterMount, notifications)
            host.removeAll()
        }
    }
    @Test fun `narrow layout does not skip the list when selectionChanged reports a selection appearing`() {
        SwingUtilities.invokeAndWait {
            val list = JLabel("list")
            val details = JLabel("details")
            var hasSelection = false
            val responsive = ResponsiveMasterDetail(list, details, "Back", hasSelection = { hasSelection })
            val host = host(responsive.component, 400)

            assertTrue(responsive.isCompact)
            assertTrue(descendants(responsive.component).contains(list))
            assertFalse(descendants(responsive.component).contains(details))

            // A programmatic auto-select (populating the list for the first time) also fires
            // selectionChanged() — it must not skip straight past the list to details, or every
            // narrow-mode tab would open on its last selection instead of its list.
            hasSelection = true
            responsive.selectionChanged()
            assertTrue(descendants(responsive.component).contains(list), "selectionChanged() alone must not reveal details")
            assertFalse(descendants(responsive.component).contains(details))

            // An explicit gesture (click/Enter) is what actually navigates.
            responsive.showDetailsNow()
            assertTrue(descendants(responsive.component).contains(details))
            assertFalse(descendants(responsive.component).contains(list))

            button(responsive.component, "Back").doClick()
            assertTrue(descendants(responsive.component).contains(list))
            assertFalse(descendants(responsive.component).contains(details))

            // Losing the selection entirely (e.g. a filter now matches nothing) does bounce back.
            responsive.showDetailsNow()
            assertTrue(descendants(responsive.component).contains(details))
            hasSelection = false
            responsive.selectionChanged()
            assertTrue(descendants(responsive.component).contains(list))
            assertFalse(descendants(responsive.component).contains(details))
            host.removeAll()
        }
    }

    @Test fun `going wide, then narrow, then wide again still attaches both list and details to the splitter`() {
        SwingUtilities.invokeAndWait {
            val list = JLabel("list")
            val details = JLabel("details")
            val responsive = ResponsiveMasterDetail(list, details, "Back", hasSelection = { true })
            val host = host(responsive.component, 900)
            assertFalse(responsive.isCompact)

            resize(host, responsive.component, 400)
            assertTrue(responsive.isCompact)

            // Regression: Splitter's setFirstComponent/setSecondComponent no-op when handed the
            // same reference they already hold, so reparenting list/details out of the splitter
            // (for the compact layout) and then back with no change in between used to leave the
            // splitter's bookkeeping stale and both panes blank.
            resize(host, responsive.component, 900)
            assertFalse(responsive.isCompact)
            val splitter = descendants(responsive.component).filterIsInstance<JBSplitter>().single()
            assertTrue(descendants(splitter).contains(list))
            assertTrue(descendants(splitter).contains(details))
            host.removeAll()
        }
    }

    @Test fun `showDetailsNow only takes effect while compact and only when something is selected`() {
        SwingUtilities.invokeAndWait {
            val list = JLabel("list")
            val details = JLabel("details")
            var hasSelection = false
            val responsive = ResponsiveMasterDetail(list, details, "Back", hasSelection = { hasSelection })
            val host = host(responsive.component, 400)

            responsive.showDetailsNow()
            assertTrue(descendants(responsive.component).contains(list), "Nothing selected yet: Enter must not reveal empty details")

            hasSelection = true
            responsive.showDetailsNow()
            assertTrue(descendants(responsive.component).contains(details))
            host.removeAll()
        }
    }

    @Test fun `compact back bar names the selected item and clears it when the selection is gone`() {
        SwingUtilities.invokeAndWait {
            val list = JLabel("list")
            val details = JLabel("details")
            var selected: String? = "AgentHub"
            val responsive = ResponsiveMasterDetail(
                list,
                details,
                "Projects",
                hasSelection = { selected != null },
                selectionTitle = { selected?.let { DetailsTitle(it) } },
            )
            val host = host(responsive.component, 400)

            // The list alone never shows a title: nothing is "open" yet.
            assertTrue(labels(responsive.component).none { it.text == "AgentHub" })

            responsive.showDetailsNow()
            assertTrue(descendants(responsive.component).contains(details))
            assertTrue(labels(responsive.component).any { it.text == "AgentHub" }, "back bar must name the open item")
            assertEquals("Projects", button(responsive.component, "Projects").text)

            // Selecting a different item while details are open re-labels the bar.
            selected = "OrvosiKortortenet"
            responsive.selectionChanged()
            assertTrue(labels(responsive.component).any { it.text == "OrvosiKortortenet" })
            assertTrue(labels(responsive.component).none { it.text == "AgentHub" })

            selected = null
            responsive.selectionChanged()
            assertTrue(descendants(responsive.component).contains(list))
            assertTrue(labels(responsive.component).none { it.text == "OrvosiKortortenet" })
            host.removeAll()
        }
    }

    @Test fun `details-only is reported while a compact layout shows the details and never in a wide layout`() {
        SwingUtilities.invokeAndWait {
            val list = JLabel("list")
            val details = JLabel("details")
            val responsive = ResponsiveMasterDetail(list, details, "Back", hasSelection = { true })
            val reports = mutableListOf<Boolean>()
            responsive.onDetailsOnlyChanged = { reports += it }
            val host = host(responsive.component, 400)

            assertTrue(reports.isEmpty(), "the list is showing: nothing to report yet")
            assertFalse(responsive.isShowingDetailsOnly)

            responsive.showDetailsNow()
            assertEquals(listOf(true), reports)
            assertTrue(responsive.isShowingDetailsOnly)

            button(responsive.component, "Back").doClick()
            assertEquals(listOf(true, false), reports)
            assertFalse(responsive.isShowingDetailsOnly)

            // Going wide while details are open ends the details-only state: both sides are visible.
            responsive.showDetailsNow()
            assertEquals(listOf(true, false, true), reports)
            resize(host, responsive.component, 900)
            assertEquals(listOf(true, false, true, false), reports)
            assertFalse(responsive.isShowingDetailsOnly)
            host.removeAll()
        }
    }

    @Test fun `details-only ends when the selection disappears`() {
        SwingUtilities.invokeAndWait {
            var hasSelection = true
            val responsive = ResponsiveMasterDetail(JLabel("list"), JLabel("details"), "Back", hasSelection = { hasSelection })
            val reports = mutableListOf<Boolean>()
            responsive.onDetailsOnlyChanged = { reports += it }
            val host = host(responsive.component, 400)

            responsive.showDetailsNow()
            hasSelection = false
            responsive.selectionChanged()

            assertEquals(listOf(true, false), reports)
            host.removeAll()
        }
    }

    @Test fun `tabs sharing a split proportion keep their dividers in sync in both directions`() {
        SwingUtilities.invokeAndWait {
            val shared = SharedSplitProportion()
            val first = ResponsiveMasterDetail(JLabel("list-1"), JLabel("details-1"), "Back", hasSelection = { true }, splitProportion = shared)
            val second = ResponsiveMasterDetail(JLabel("list-2"), JLabel("details-2"), "Back", hasSelection = { true }, splitProportion = shared)
            val firstHost = host(first.component, 900)
            val secondHost = host(second.component, 900)
            val firstSplitter = descendants(first.component).filterIsInstance<JBSplitter>().single()
            val secondSplitter = descendants(second.component).filterIsInstance<JBSplitter>().single()

            assertEquals(SharedSplitProportion.DEFAULT, firstSplitter.proportion, 0.0001f)
            assertEquals(firstSplitter.proportion, secondSplitter.proportion, 0.0001f)

            // Dragging the divider on one tab moves it on the other...
            firstSplitter.proportion = 0.6f
            assertEquals(0.6f, secondSplitter.proportion, 0.0001f)
            assertEquals(0.6f, shared.value, 0.0001f)

            // ...and the other way round.
            secondSplitter.proportion = 0.35f
            assertEquals(0.35f, firstSplitter.proportion, 0.0001f)
            firstHost.removeAll()
            secondHost.removeAll()
        }
    }

    @Test fun `a tab that was narrow comes back wide at the shared proportion, not its own`() {
        SwingUtilities.invokeAndWait {
            val shared = SharedSplitProportion()
            val responsive = ResponsiveMasterDetail(JLabel("list"), JLabel("details"), "Back", hasSelection = { true }, splitProportion = shared)
            val host = host(responsive.component, 900)
            resize(host, responsive.component, 400)

            shared.update(0.55f)
            resize(host, responsive.component, 900)

            val splitter = descendants(responsive.component).filterIsInstance<JBSplitter>().single()
            assertEquals(0.55f, splitter.proportion, 0.0001f)
            host.removeAll()
        }
    }

    private fun labels(root: Container) = descendants(root).filterIsInstance<JLabel>()

    private fun host(component: javax.swing.JComponent, width: Int): JPanel {
        val host = JPanel()
        host.add(component)
        host.addNotify()
        resize(host, component, width)
        return host
    }

    private fun resize(host: JPanel, component: javax.swing.JComponent, width: Int) {
        host.setSize(width, 600)
        component.setSize(width, 600)
        component.dispatchEvent(ComponentEvent(component, ComponentEvent.COMPONENT_RESIZED))
    }

    private fun button(root: Container, text: String) =
        descendants(root).filterIsInstance<javax.swing.JButton>().first { it.text == text }

    companion object {
        @JvmStatic @BeforeAll fun setupScaling() { JBUIScale.setSystemScaleFactor(1.0f) }
    }
}
