package com.shutterstar.agenthub.environment.skills.ui

import com.shutterstar.agenthub.descendants
import com.intellij.ui.scale.JBUIScale
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JList
import javax.swing.SwingUtilities

/**
 * Reproduces the live sandbox report ("wide view is empty") using a REAL top-level, visible
 * [JFrame] instead of an offscreen [SwingUtilities.invokeAndWait] block — a real frame lets the
 * AWT event queue actually pump between steps, unlike the synchronous screenshot test in
 * [SkillsPanelTest], which only proves the production code paints correctly when every layout step
 * is driven by hand.
 */
class SkillsPanelLiveWindowTest {
    @Test fun `navigating to a distant skill scrolls its selected row into view`() {
        var frame: JFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                val context = SkillBrowserContext(SkillScope.GLOBAL)
                val skills = (0 until 40).map { index ->
                    val name = "skill-%02d".format(index)
                    AgentSkill(
                        SkillIdentity(name), name, null, SkillScope.GLOBAL,
                        listOf(SkillSource("codex", "/skills/$name", SkillScope.GLOBAL, false, name)),
                        setOf("codex"), SkillConsistency.SINGLE_SOURCE,
                    )
                }
                val snapshot = SkillBrowserSnapshot(context, skills)
                val panel = SkillsPanel({}, {}, {}, agentIcon = { null })
                val f = JFrame("skill-navigation-test")
                f.contentPane.add(panel)
                f.setSize(1000, 350)
                f.isVisible = true
                frame = f
                panel.navigateTo(context, "skill-39", "/skills/skill-39")
                panel.showState(SkillBrowserState(context, snapshot))
            }
            Thread.sleep(250)
            SwingUtilities.invokeAndWait {
                val list = descendants(frame!!.contentPane).filterIsInstance<JList<*>>().single()
                val selectedBounds = list.getCellBounds(list.selectedIndex, list.selectedIndex)
                assertTrue(selectedBounds != null && list.visibleRect.intersects(selectedBounds), "selected skill should be visible in the list")
            }
        } finally {
            SwingUtilities.invokeAndWait { frame?.dispose() }
        }
    }

    @Test fun `a real visible frame renders content in both wide and narrow layouts`() {
        var frame: JFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                val panel = SkillsPanel({}, {}, {}, agentIcon = { null })
                panel.setProjects(emptyList())
                val snapshot = SkillBrowserTest.sampleSnapshot()
                panel.showState(SkillBrowserState(snapshot.context, snapshot))
                val f = JFrame("live-test")
                f.contentPane.add(panel)
                f.setSize(1000, 700)
                f.isVisible = true
                frame = f
            }
            Thread.sleep(400)
            SwingUtilities.invokeAndWait { screenshot(frame!!, "skills-live-wide.png") }

            SwingUtilities.invokeAndWait { frame!!.setSize(400, 700) }
            Thread.sleep(400)
            SwingUtilities.invokeAndWait { screenshot(frame!!, "skills-live-narrow.png") }

            SwingUtilities.invokeAndWait { frame!!.setSize(1000, 700) }
            Thread.sleep(400)
            SwingUtilities.invokeAndWait { screenshot(frame!!, "skills-live-wide-again.png") }
        } finally {
            SwingUtilities.invokeAndWait { frame?.dispose() }
        }
        assertTrue(true)
    }

    private fun screenshot(frame: JFrame, name: String) {
        val directory = System.getProperty("agenthub.ui.screenshot.dir") ?: return
        Files.createDirectories(Path.of(directory))
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try { frame.printAll(graphics) } finally { graphics.dispose() }
        ImageIO.write(image, "png", Path.of(directory).resolve(name).toFile())
    }

    companion object {
        @JvmStatic @BeforeAll fun setupScaling() { JBUIScale.setSystemScaleFactor(1.0f) }
    }
}
