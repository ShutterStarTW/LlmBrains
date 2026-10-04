package com.shutterstar.agenthub.environment.skills.ui

import com.shutterstar.agenthub.descendants
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.shutterstar.agenthub.environment.model.EnvironmentWarning
import com.shutterstar.agenthub.projects.ui.CollapsibleWarningBar
import com.shutterstar.agenthub.projects.ui.DetailsHeader
import com.shutterstar.agenthub.projects.ui.LeftAlignedTabbedPane
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAction
import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAuditEntry
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.Container
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class SkillsPanelTest {
    @Test fun `refresh retains the skill list scroll pane while data is unchanged`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel({}, {}, {}, agentIcon = { null })
            val snapshot = SkillBrowserTest.sampleSnapshot()
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            val scroll = descendants(panel).filterIsInstance<javax.swing.JScrollPane>()
                .first { it.viewport.view is javax.swing.JList<*> }

            panel.showState(SkillBrowserState(snapshot.context, snapshot, loading = true))
            panel.showState(SkillBrowserState(snapshot.context, snapshot))

            assertTrue(descendants(panel).contains(scroll))
        }
    }
    @Test fun `selecting another skill starts its overview at the top after scrolling`() {
        lateinit var panel: SkillsPanel
        fun overviewScroll(): javax.swing.JScrollPane = descendants(panel)
            .filterIsInstance<javax.swing.JScrollPane>()
            .first { scroll -> descendants(scroll).filterIsInstance<JTextArea>().any { it.text.startsWith("Long description") } }
        SwingUtilities.invokeAndWait {
            panel = SkillsPanel({}, {}, {}, agentIcon = { null })
            val snapshot = SkillBrowserTest.sampleSnapshot()
            val first = snapshot.skills.single().copy(description = "Long description\n" + "Another line of skill documentation.\n".repeat(50))
            val second = first.copy(
                identity = SkillIdentity("second-skill"), name = "second-skill",
                sources = first.sources.map { it.copy(path = it.path + "-second", displayTitle = "Second skill") },
            )
            panel.showState(SkillBrowserState(snapshot.context, snapshot.copy(skills = listOf(first, second))))
            panel.addNotify()
            resize(panel, 1000, 650)
            panel.validate()
        }
        try {
            SwingUtilities.invokeAndWait {
                val scroll = overviewScroll()
                scroll.verticalScrollBar.value = 250
                assertTrue(scroll.viewport.viewPosition.y > 0, "The first skill must be scrolled before switching")
                val list = descendants(panel).filterIsInstance<javax.swing.JList<*>>().first()
                list.selectedIndex = (0 until list.model.size).first {
                    (list.model.getElementAt(it) as SkillOccurrenceRow).skill.identity.id == "second-skill"
                }
                resize(panel, 1000, 650)
                panel.validate()
            }
            // Let deferred caret visibility and layout requests run before checking the new page.
            SwingUtilities.invokeAndWait {
                assertEquals(0, overviewScroll().viewport.viewPosition.y)
                assertTrue(descendants(overviewScroll()).filterIsInstance<JTextArea>().all { it.caretPosition == 0 })
            }
        } finally {
            SwingUtilities.invokeAndWait { panel.removeNotify() }
        }
    }
    @Test fun `a new tab visit resets skill navigation and filters`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel({}, {}, {}, agentIcon = { null })
            val snapshot = SkillBrowserTest.sampleSnapshot()
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            val tabs = descendants(panel).filterIsInstance<LeftAlignedTabbedPane>().single()
            tabs.select("Agents")
            panel.setFilters(SkillBrowserFilter.SHARED, SkillOwnershipFilter.ALL, null)
            panel.setQuery("no-match")

            panel.showDefaultContent()

            assertEquals(SkillBrowserFilter.ALL, panel.activeStateFilter)
            assertEquals("Overview", tabs.selectedTitle())
            assertTrue(panel.occurrenceCount > 0)
            assertEquals(0, descendants(panel).filterIsInstance<javax.swing.JList<*>>().first().selectedIndex)
        }
    }
    @Test fun `should filter and open the selected source without filesystem mutation`() {
        SwingUtilities.invokeAndWait {
            val opened = mutableListOf<Path>()
            val shared = mutableListOf<SkillOccurrenceRow>()
            val promoted = mutableListOf<SkillOccurrenceRow>()
            val resynced = mutableListOf<Pair<SkillOccurrenceRow, String>>()
            val stopped = mutableListOf<Pair<SkillOccurrenceRow, String>>()
            val undone = mutableListOf<String>()
            val backupsRestored = mutableListOf<SkillOccurrenceRow>()
            val retried = mutableListOf<SkillOccurrenceRow>()
            val repairedAll = mutableListOf<SkillOccurrenceRow>()
            val conflictsResolved = mutableListOf<Pair<SkillOccurrenceRow, VersionSides>>()
            val panel = SkillsPanel(
                requestContext = {},
                openSkill = opened::add,
                revealSkill = {},
                shareSkill = shared::add,
                promoteSkill = promoted::add,
                resyncSkill = { row, agentId -> resynced.add(row to agentId) },
                stopSharingSkill = { row, agentId -> stopped.add(row to agentId) },
                resolveVersions = { row, sides -> conflictsResolved.add(row to sides) },
                hasHistory = { row -> row.source.shared },
                historyEntries = { row ->
                    if (row.source.shared) {
                        listOf(
                            SkillHistoryItem(
                                SyncAuditEntry("op-1", java.time.Instant.parse("2026-10-03T08:00:00Z"), "review", SyncAction.SHARE, setOf("claude"), SyncOperationStatus.SUCCESS),
                                undoable = true,
                            ),
                            SkillHistoryItem(
                                SyncAuditEntry("op-0", java.time.Instant.parse("2026-10-02T08:00:00Z"), "review", SyncAction.PROMOTE, setOf("claude"), SyncOperationStatus.SUCCESS),
                                undoable = false,
                                backupRemoved = true,
                            ),
                        )
                    } else {
                        emptyList()
                    }
                },
                undoOperation = undone::add,
                restoreBackup = backupsRestored::add,
                hasBackups = { row -> row.source.shared },
                retryFailedTargetIds = { row -> if (row.source.shared) setOf("codex") else emptySet() },
                retryFailedTargets = retried::add,
                repairAllSkill = repairedAll::add,
                managedTargetIds = { row -> if (row.source.shared) setOf("claude") else emptySet() },
                syncTargetIds = { setOf("claude") },
                agentIcon = { null },
            )
            panel.setProjects(emptyList())
            val snapshot = SkillBrowserTest.sampleSnapshot()
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            assertEquals(2, panel.occurrenceCount)
            button(panel, "Share with agents…").doClick()
            assertEquals(1, shared.size)
            // The History page lists every recorded operation itself; only a still reversible one has Undo.
            val historyTexts = descendants(panel).filterIsInstance<javax.swing.JLabel>().map { it.text.orEmpty() }
            assertEquals(2, historyTexts.count { " · Share · Succeeded" in it || " · Promote · Succeeded" in it })
            assertTrue(descendants(panel).filterIsInstance<JButton>().none { it.text == "View Sync History" })
            assertEquals(1, historyTexts.count { it.startsWith("Undo unavailable") }, "only the entry that lost a backup explains the missing Undo")
            button(panel, "Undo").doClick()
            assertEquals(listOf("op-1"), undone)
            button(panel, "Restore Backup…").doClick()
            assertEquals(1, backupsRestored.size)
            button(panel, "Retry Failed Targets (1)").doClick()
            assertEquals(1, retried.size)
            button(panel, "Repair All").doClick()
            assertEquals(1, repairedAll.size)
            button(panel, "Resync").doClick()
            assertEquals(listOf("claude"), resynced.map { it.second })
            button(panel, "Stop Sharing").doClick()
            assertEquals(listOf("claude"), stopped.map { it.second })
            panel.setQuery(".claude")
            assertEquals(1, panel.occurrenceCount)
            val openButton = button(panel, "Open SKILL.md")
            assertEquals("", openButton.text)
            assertTrue(openButton.icon != null)
            assertTrue(openButton.toolTipText.contains("Open SKILL.md"))
            val revealButton = button(panel, "Reveal in Files")
            assertEquals("", revealButton.text)
            assertTrue(revealButton.icon != null)
            assertTrue(revealButton.toolTipText.contains("Reveal in Files"))
            openButton.doClick()
            // The sample snapshot's non-shared occurrence has DIFFERENT consistency and a shared
            // sibling, i.e. it's already-promoted-but-diverged - "Resolve Conflict…" replaces
            // "Promote to Shared" for that combination (see the dedicated test below).
            button(panel, "Resolve Conflict…").doClick()
            assertEquals(listOf(panel.selectedOccurrence!!.skillFile), opened)
            assertEquals(listOf("claude"), conflictsResolved.map { it.first.source.agentId })
            panel.setQuery("no-match")
            assertEquals(0, panel.occurrenceCount)
            button(panel, "Clear filters").doClick()
            assertEquals(2, panel.occurrenceCount)
        }
    }

    @Test fun `Promote to Shared still shows for a not-yet-promoted, single-source occurrence`() {
        SwingUtilities.invokeAndWait {
            val promoted = mutableListOf<SkillOccurrenceRow>()
            val panel = SkillsPanel(
                requestContext = {},
                openSkill = {},
                revealSkill = {},
                promoteSkill = promoted::add,
                syncTargetIds = { setOf("claude") },
                agentIcon = { null },
            )
            panel.setProjects(emptyList())
            val sample = SkillBrowserTest.sampleSnapshot()
            val skill = sample.skills.single()
            // No shared sibling and SINGLE_SOURCE consistency - nothing to conflict against yet.
            val unpromoted = sample.copy(
                skills = listOf(skill.copy(sources = skill.sources.filterNot { it.shared }, consistency = com.shutterstar.agenthub.environment.skills.model.SkillConsistency.SINGLE_SOURCE)),
            )
            panel.showState(SkillBrowserState(unpromoted.context, unpromoted))

            assertEquals(1, panel.occurrenceCount)
            button(panel, "Move to Shared & Share…").doClick()
            assertEquals(1, promoted.size)
            assertTrue(descendants(panel).filterIsInstance<JButton>().none { it.text == "Resolve Conflict…" })
        }
    }

    @Test fun `Resolve Conflict is offered for differing agent copies that are not shared yet`() {
        SwingUtilities.invokeAndWait {
            val resolved = mutableListOf<VersionSides>()
            val panel = SkillsPanel(
                requestContext = {},
                openSkill = {},
                revealSkill = {},
                resolveVersions = { _, sides -> resolved += sides },
                syncTargetIds = { setOf("claude", "codex") },
                agentIcon = { null },
            )
            panel.setProjects(emptyList())
            val sources = listOf(
                SkillSource("claude", "C:/Users/example/.claude/skills/php-review", SkillScope.GLOBAL, false, "B", "PHP Review"),
                SkillSource("codex", "C:/Users/example/.codex/skills/php-review", SkillScope.GLOBAL, false, "C", "PHP Review"),
            )
            val skill = AgentSkill(SkillIdentity("php-review"), "php-review", "desc", SkillScope.GLOBAL, sources, setOf("claude", "codex"), SkillConsistency.DIFFERENT)
            val snapshot = SkillBrowserSnapshot(SkillBrowserContext(SkillScope.GLOBAL), listOf(skill))
            panel.showState(SkillBrowserState(snapshot.context, snapshot))

            button(panel, "Resolve Conflict…").doClick()
            assertEquals(listOf(listOf("codex")), resolved.map { sides -> sides.others.map { it.agentId } })
            // The opened skill has no compare button of its own; it keeps its own Share… action.
            assertTrue(descendants(panel).filterIsInstance<JButton>().any { it.text == "Move to Shared & Share…" })
        }
    }

    @Test fun `Go to skill selects the other copy and clears the search that hid it`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel(requestContext = {}, openSkill = {}, revealSkill = {}, syncTargetIds = { setOf("claude", "codex") }, agentIcon = { null })
            panel.setProjects(emptyList())
            val sources = listOf(
                SkillSource(null, "C:/Users/example/.agents/skills/php-review", SkillScope.GLOBAL, true, "A", "PHP Review"),
                SkillSource("claude", "C:/Users/example/.claude/skills/php-review", SkillScope.GLOBAL, false, "B", "PHP Review"),
            )
            val skill = AgentSkill(SkillIdentity("php-review"), "php-review", "desc", SkillScope.GLOBAL, sources, setOf("claude"), SkillConsistency.DIFFERENT)
            val snapshot = SkillBrowserSnapshot(SkillBrowserContext(SkillScope.GLOBAL), listOf(skill))
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            panel.setQuery(".agents")
            assertTrue(panel.selectedOccurrence!!.source.shared)

            button(panel, "Go to skill").doClick()
            assertEquals("C:/Users/example/.claude/skills/php-review", panel.selectedOccurrence!!.source.path)
            assertEquals(2, panel.occurrenceCount)
        }
    }

    @Test fun `conflict options list each folder once under the agent that owns it`() {
        SwingUtilities.invokeAndWait {
            val resolved = mutableListOf<VersionSides>()
            val panel = SkillsPanel(
                requestContext = {},
                openSkill = {},
                revealSkill = {},
                resolveVersions = { _, sides -> resolved += sides },
                owningAgent = { path, candidates, _, _ -> if (path.contains(".claude")) "claude".takeIf { it in candidates } else candidates.firstOrNull() },
                syncTargetIds = { setOf("claude", "cursor", "grok", "antigravity") },
                agentIcon = { null },
            )
            panel.setProjects(emptyList())
            val claudeDir = "C:/Users/example/.claude/skills/url-checker"
            val sources = listOf(
                SkillSource("claude", claudeDir, SkillScope.GLOBAL, false, "B", "URL Checker"),
                SkillSource("cursor", claudeDir, SkillScope.GLOBAL, false, "B", "URL Checker"),
                SkillSource("grok", claudeDir, SkillScope.GLOBAL, false, "B", "URL Checker"),
                SkillSource("antigravity", "C:/Users/example/.gemini/skills/url-checker", SkillScope.GLOBAL, false, "C", "URL Checker"),
            )
            val skill = AgentSkill(SkillIdentity("url-checker"), "url-checker", "desc", SkillScope.GLOBAL, sources, setOf("claude"), SkillConsistency.DIFFERENT)
            val snapshot = SkillBrowserSnapshot(SkillBrowserContext(SkillScope.GLOBAL), listOf(skill))
            panel.showState(SkillBrowserState(snapshot.context, snapshot))

            button(panel, "Resolve Conflict…").doClick()
            assertEquals(listOf("claude" to listOf("antigravity")), resolved.map { sides -> sides.current.agentId to sides.others.map { it.agentId } })
        }
    }

    @Test fun `vendor-provided conflicting copies get a resolve button too`() {
        SwingUtilities.invokeAndWait {
            val compared = mutableListOf<VersionSides>()
            val panel = SkillsPanel(
                requestContext = {},
                openSkill = {},
                revealSkill = {},
                resolveVersions = { _, sides -> compared += sides },
                syncTargetIds = { setOf("claude", "codex") },
                agentIcon = { null },
            )
            panel.setProjects(emptyList())
            val sources = listOf(
                SkillSource("claude", "C:/Users/example/.claude/skills/synced/x/skill-creator", SkillScope.GLOBAL, false, "B", "Skill Creator", system = true),
                SkillSource("codex", "C:/Users/example/.codex/skills/.system/skill-creator", SkillScope.GLOBAL, false, "C", "Skill Creator", system = true),
            )
            val skill = AgentSkill(SkillIdentity("skill-creator"), "skill-creator", "desc", SkillScope.GLOBAL, sources, setOf("claude", "codex"), SkillConsistency.DIFFERENT)
            val snapshot = SkillBrowserSnapshot(SkillBrowserContext(SkillScope.GLOBAL), listOf(skill))
            panel.showState(SkillBrowserState(snapshot.context, snapshot))

            button(panel, "Resolve Conflict…").doClick()
            assertEquals(listOf("claude" to listOf("codex")), compared.map { sides -> sides.current.agentId to sides.others.map { it.agentId } })
            assertTrue(descendants(panel).filterIsInstance<JButton>().none { it.text == "Compare Versions…" })
        }
    }

    @Test fun `an agent copy is compared with every other source, the opened skill always being the left side`() {
        SwingUtilities.invokeAndWait {
            val opened = mutableListOf<VersionSides>()
            val panel = SkillsPanel(
                requestContext = {},
                openSkill = {},
                revealSkill = {},
                resolveVersions = { _, sides -> opened += sides },
                syncTargetIds = { setOf("claude", "codex") },
                agentIcon = { null },
            )
            panel.setProjects(emptyList())
            val sources = listOf(
                SkillSource(null, "C:/Users/example/.agents/skills/php-review", SkillScope.GLOBAL, true, "A", "PHP Review"),
                SkillSource("claude", "C:/Users/example/.claude/skills/php-review", SkillScope.GLOBAL, false, "B", "PHP Review"),
                SkillSource("codex", "C:/Users/example/.codex/skills/php-review", SkillScope.GLOBAL, false, "C", "PHP Review"),
            )
            val skill = AgentSkill(SkillIdentity("php-review"), "php-review", "desc", SkillScope.GLOBAL, sources, setOf("claude", "codex"), SkillConsistency.DIFFERENT)
            val snapshot = SkillBrowserSnapshot(SkillBrowserContext(SkillScope.GLOBAL), listOf(skill))
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            panel.setQuery(".claude")
            assertEquals("claude", panel.selectedOccurrence!!.source.agentId)

            val buttons = descendants(panel).filterIsInstance<JButton>()
            // Both the shared source and the other agent's copy can be resolved against (neither is a vendor
            // copy). The opened skill itself has no compare button - everything else is compared with it.
            val resolve = buttons.filter { it.text == "Resolve Conflict…" }
            assertEquals(2, resolve.size)
            assertTrue(buttons.none { it.text == "Compare Versions…" })
            resolve.forEach { it.doClick() }
            assertTrue(opened.all { it.current.agentId == "claude" && it.sharedExists })
            assertEquals(setOf(null, "codex"), opened.flatMap { sides -> sides.others.map { it.agentId } }.toSet())
        }
    }

    @Test fun `identical and linked sources get no compare button, only sources that really differ do`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel(
                requestContext = {},
                openSkill = {},
                revealSkill = {},
                resolveVersions = { _, _ -> },
                syncTargetIds = { setOf("claude", "codex", "cursor", "grok") },
                agentIcon = { null },
            )
            panel.setProjects(emptyList())
            val sources = listOf(
                SkillSource("claude", "C:/Users/example/.claude/skills/php-review", SkillScope.GLOBAL, false, "B", "PHP Review"),
                SkillSource("codex", "C:/Users/example/.codex/skills/php-review", SkillScope.GLOBAL, false, "B", "PHP Review"),
                SkillSource("cursor", "C:/Users/example/.cursor/skills/php-review", SkillScope.GLOBAL, false, "C", "PHP Review"),
                SkillSource("grok", "C:/Users/example/.grok/skills/php-review", SkillScope.GLOBAL, false, "D", "PHP Review"),
            )
            val skill = AgentSkill(SkillIdentity("php-review"), "php-review", "desc", SkillScope.GLOBAL, sources, setOf("claude"), SkillConsistency.DIFFERENT)
            val snapshot = SkillBrowserSnapshot(
                SkillBrowserContext(SkillScope.GLOBAL),
                listOf(skill),
                // Grok's folder is only a link (e.g. into the shared source): it is not a version of its own.
                sourceStats = mapOf(sources[3].path to SourceStat(1, 10, isLink = true)),
            )
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            panel.setQuery(".claude")
            assertEquals("claude", panel.selectedOccurrence!!.source.agentId)

            // Only Cursor's copy differs from the opened Claude copy: Codex is identical, Grok is a link.
            val compareButtons = descendants(panel).filterIsInstance<JButton>().filter { it.text == "Compare Versions…" || it.text == "Resolve Conflict…" }
            assertEquals(1, compareButtons.size)
        }
    }

    @Test fun `Restore Backup button and History entries are hidden when there is nothing to show`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel(
                requestContext = {},
                openSkill = {},
                revealSkill = {},
                hasHistory = { false },
                hasBackups = { false },
                agentIcon = { null },
            )
            panel.setProjects(emptyList())
            val snapshot = SkillBrowserTest.sampleSnapshot()
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            panel.setQuery(".agents")
            assertEquals(1, panel.occurrenceCount)
            assertTrue(panel.selectedOccurrence!!.source.shared)

            val buttonLabels = descendants(panel).filterIsInstance<JButton>().map { it.text }
            assertTrue("Undo" !in buttonLabels)
            assertTrue("Restore Backup…" !in buttonLabels)
        }
    }

    @Test fun `ownership filter narrows occurrences and Clear filters resets it`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel(
                requestContext = {},
                openSkill = {},
                revealSkill = {},
                isManagedOccurrence = { row -> !row.source.shared },
                agentIcon = { null },
            )
            panel.setProjects(emptyList())
            panel.showState(SkillBrowserState(SkillBrowserTest.sampleSnapshot().context, SkillBrowserTest.sampleSnapshot()))
            assertEquals(2, panel.occurrenceCount)

            panel.setFilters(SkillBrowserFilter.ALL, SkillOwnershipFilter.MANAGED, null)
            assertEquals(1, panel.occurrenceCount)
            assertTrue(!panel.selectedOccurrence!!.source.shared)

            panel.setFilters(SkillBrowserFilter.ALL, SkillOwnershipFilter.UNMANAGED, null)
            assertEquals(1, panel.occurrenceCount)
            assertTrue(panel.selectedOccurrence!!.source.shared)

            // Clearing must also work when an additional search removes every occurrence.
            panel.setQuery("no-match")
            assertEquals(0, panel.occurrenceCount)
            button(panel, "Clear filters").doClick()
            assertEquals(SkillOwnershipFilter.ALL, panel.activeOwnershipFilter)
            assertEquals(2, panel.occurrenceCount)
        }
    }

    @Test fun `active skill filters show counts and a centered reset button even with no matches`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel({}, {}, {}, agentIcon = { null }, displayName = { "Agent $it" })
            val snapshot = SkillBrowserTest.sampleSnapshot()
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            val summary = descendants(panel).filterIsInstance<JTextArea>().single { it.text.startsWith("2 of 2 items") }
            val status = summary.parent.parent as JPanel
            val clear = button(panel, "Clear filters")
            var searchCleared = 0
            panel.onClearSearch = { searchCleared++ }
            assertFalse(status.isVisible)

            panel.setFilters(SkillBrowserFilter.SHARED, SkillOwnershipFilter.ALL, null)
            assertTrue(status.isVisible)
            assertEquals("1 of 2 items · State: Shared", summary.text)
            assertTrue(clear.isVisible)
            resize(panel, 1000, 650)
            val summaryY = SwingUtilities.convertPoint(summary, 0, 0, status).y
            val buttonY = SwingUtilities.convertPoint(clear, 0, 0, status).y
            assertTrue(kotlin.math.abs(summaryY * 2 + summary.height - buttonY * 2 - clear.height) <= 1)
            clear.doClick()
            assertFalse(status.isVisible)
            assertEquals(2, panel.occurrenceCount)

            panel.setQuery("no-match")
            assertEquals("0 of 2 items · Search: no-match", summary.text)
            assertTrue(status.isVisible)
            assertTrue(descendants(panel).contains(clear))
            clear.doClick()
            assertFalse(status.isVisible)
            assertEquals(2, panel.occurrenceCount)
            assertEquals(2, searchCleared)

            panel.setFilters(SkillBrowserFilter.ALL, SkillOwnershipFilter.UNMANAGED, "claude")
            assertTrue(summary.text.contains("Ownership: Not managed"))
            assertTrue(summary.text.contains("Agent: Agent claude"))
            clear.doClick()
            assertEquals(SkillOwnershipFilter.ALL, panel.activeOwnershipFilter)
            assertEquals(null, panel.activeAgentFilter)
            assertFalse(status.isVisible)
        }
    }
    @Test fun `the Filters button counts the active filters and Clear filters resets state ownership and agent`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel(requestContext = {}, openSkill = {}, revealSkill = {}, agentIcon = { null })
            panel.setProjects(emptyList())
            panel.showState(SkillBrowserState(SkillBrowserTest.sampleSnapshot().context, SkillBrowserTest.sampleSnapshot()))
            assertEquals("Filters \u25be", panel.filtersButtonText)

            panel.setFilters(SkillBrowserFilter.SHARED, SkillOwnershipFilter.ALL, null)
            assertEquals("Filters (1) \u25be", panel.filtersButtonText)
            assertEquals(1, panel.occurrenceCount)

            // The shared occurrence has no agent of its own, so state + ownership + agent leaves nothing.
            panel.setFilters(SkillBrowserFilter.SHARED, SkillOwnershipFilter.UNMANAGED, "claude")
            assertEquals("Filters (3) \u25be", panel.filtersButtonText)
            assertEquals(0, panel.occurrenceCount)

            button(panel, "Clear filters").doClick()
            assertEquals("Filters \u25be", panel.filtersButtonText)
            assertEquals(SkillBrowserFilter.ALL, panel.activeStateFilter)
            assertEquals(SkillOwnershipFilter.ALL, panel.activeOwnershipFilter)
            assertEquals(null, panel.activeAgentFilter)
            assertEquals(2, panel.occurrenceCount)
        }
    }

    @Test fun `an agent filter for an agent the refreshed scope no longer has is dropped`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel(requestContext = {}, openSkill = {}, revealSkill = {}, agentIcon = { null })
            panel.setProjects(emptyList())
            val snapshot = SkillBrowserTest.sampleSnapshot()
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            panel.setFilters(SkillBrowserFilter.ALL, SkillOwnershipFilter.ALL, "claude")
            assertEquals(1, panel.occurrenceCount)

            // Same scope, but the claude source is gone: filtering for it would hide everything.
            val skill = snapshot.skills.single()
            val withoutClaude = snapshot.copy(skills = listOf(skill.copy(sources = skill.sources.filter { it.agentId != "claude" })))
            panel.showState(SkillBrowserState(withoutClaude.context, withoutClaude))

            assertEquals(null, panel.activeAgentFilter)
            assertEquals("Filters \u25be", panel.filtersButtonText)
            assertEquals(1, panel.occurrenceCount)
        }
    }

    @Test fun `a shared skill has Overview Agents and History tabs while an agent-specific one only shows its overview`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel(
                requestContext = {},
                openSkill = {},
                revealSkill = {},
                hasHistory = { row -> row.source.shared },
                syncTargetIds = { setOf("claude") },
                agentIcon = { null },
            )
            panel.setProjects(emptyList())
            val snapshot = SkillBrowserTest.sampleSnapshot()
            panel.showState(SkillBrowserState(snapshot.context, snapshot))

            panel.setQuery(".agents")
            assertTrue(panel.selectedOccurrence!!.source.shared)
            assertEquals(listOf("Overview", "Agents", "History"), tabTitles(panel))

            // A lone Overview tab would be a strip with nothing to choose: the page shows directly.
            panel.setQuery(".claude")
            assertTrue(!panel.selectedOccurrence!!.source.shared)
            assertTrue(descendants(panel).filterIsInstance<LeftAlignedTabbedPane>().isEmpty())
            button(panel, "Open SKILL.md")
        }
    }

    @Test fun `the History tab only exists when there is history or a backup to restore`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel(requestContext = {}, openSkill = {}, revealSkill = {}, hasHistory = { false }, hasBackups = { false }, agentIcon = { null })
            panel.setProjects(emptyList())
            val snapshot = SkillBrowserTest.sampleSnapshot()
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            panel.setQuery(".agents")

            assertEquals(listOf("Overview", "Agents"), tabTitles(panel))

            val withBackup = SkillsPanel(requestContext = {}, openSkill = {}, revealSkill = {}, hasHistory = { false }, hasBackups = { true }, agentIcon = { null })
            withBackup.setProjects(emptyList())
            withBackup.showState(SkillBrowserState(snapshot.context, snapshot))
            withBackup.setQuery(".agents")

            assertEquals(listOf("Overview", "Agents", "History"), tabTitles(withBackup))
            button(withBackup, "Restore Backup\u2026")
        }
    }

    @Test fun `refreshing the same skill keeps the tab being read while switching skill opens the first tab`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel(requestContext = {}, openSkill = {}, revealSkill = {}, hasHistory = { row -> row.source.shared }, agentIcon = { null })
            panel.setProjects(emptyList())
            val snapshot = SkillBrowserTest.sampleSnapshot()
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            panel.setQuery(".agents")
            val tabs = descendants(panel).filterIsInstance<LeftAlignedTabbedPane>().single()

            tabs.select("Agents")
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            assertEquals("Agents", tabs.selectedTitle())

            // Another skill in between, then back: a different entity always starts on Overview.
            panel.setQuery(".claude")
            panel.setQuery(".agents")
            assertEquals("Overview", descendants(panel).filterIsInstance<LeftAlignedTabbedPane>().single().selectedTitle())
        }
    }

    @Test fun `the Agents tab lists managed agents with their own Resync and Stop Sharing actions`() {
        SwingUtilities.invokeAndWait {
            val resynced = mutableListOf<String>()
            val stopped = mutableListOf<String>()
            val repaired = mutableListOf<SkillOccurrenceRow>()
            val panel = SkillsPanel(
                requestContext = {},
                openSkill = {},
                revealSkill = {},
                resyncSkill = { _, agentId -> resynced += agentId },
                stopSharingSkill = { _, agentId -> stopped += agentId },
                repairAllSkill = repaired::add,
                managedTargetIds = { row -> if (row.source.shared) setOf("claude", "codex") else emptySet() },
                agentIcon = { null },
            )
            panel.setProjects(emptyList())
            val snapshot = SkillBrowserTest.sampleSnapshot()
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            panel.setQuery(".agents")

            val resyncButtons = descendants(panel).filterIsInstance<JButton>().filter { it.text == "Resync" }
            assertEquals(2, resyncButtons.size, "one block per managed agent")
            resyncButtons.forEach { it.doClick() }
            descendants(panel).filterIsInstance<JButton>().filter { it.text == "Stop Sharing" }.first().doClick()
            button(panel, "Repair All").doClick()

            assertEquals(2, resynced.size)
            assertEquals(1, stopped.size)
            assertEquals(1, repaired.size)
        }
    }

    @Test fun `the Agents tab offers Stop Sharing for an existing link only while manage existing is on`() {
        SwingUtilities.invokeAndWait {
            fun stopButtons(manageExisting: Boolean): Int {
                val panel = SkillsPanel(
                    requestContext = {},
                    openSkill = {},
                    revealSkill = {},
                    manageExistingTargets = { manageExisting },
                    agentIcon = { null },
                )
                panel.setProjects(emptyList())
                val sample = SkillBrowserTest.sampleSnapshot()
                val skillId = sample.skills.single().identity.id
                val existingLink = com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget(
                    "codex",
                    Path.of("/home/user/.codex/skills/review"),
                    com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus.LINKED,
                )
                val snapshot = sample.copy(targetStatuses = mapOf(skillId to listOf(existingLink)))
                panel.showState(SkillBrowserState(snapshot.context, snapshot))
                panel.setQuery(".agents")
                return descendants(panel).filterIsInstance<JButton>().count { it.text == "Stop Sharing" }
            }

            assertEquals(0, stopButtons(manageExisting = false), "AgentHub did not create this link: no button by default")
            assertEquals(1, stopButtons(manageExisting = true))
        }
    }

    @Test fun `discovery warnings sit in a collapsed bar instead of taking header space`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel(requestContext = {}, openSkill = {}, revealSkill = {}, agentIcon = { null })
            panel.setProjects(emptyList())
            val sample = SkillBrowserTest.sampleSnapshot()
            val bar = descendants(panel).filterIsInstance<CollapsibleWarningBar>().single()
            assertFalse(bar.isVisible)

            val withWarning = sample.copy(warnings = listOf(EnvironmentWarning("skills", "claude", "global", "Unreadable directory")))
            panel.showState(SkillBrowserState(withWarning.context, withWarning))
            assertTrue(bar.isVisible)
            assertEquals("1 discovery warning", bar.toggleText)
            assertFalse(bar.isExpanded)
            assertTrue(descendants(bar).filterIsInstance<JTextArea>().any { it.text == "Unreadable directory" })

            panel.showState(SkillBrowserState(sample.context, sample))
            assertFalse(bar.isVisible)
        }
    }

    @Test fun `settings button opens the sync settings dialog`() {
        SwingUtilities.invokeAndWait {
            var opened = 0
            val panel = SkillsPanel(requestContext = {}, openSkill = {}, revealSkill = {}, openSettings = { opened++ }, agentIcon = { null })
            panel.setProjects(emptyList())

            descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == "Skill sync settings" }.doClick()

            assertEquals(1, opened)
        }
    }

    @Test fun `identical agent duplicates expose the reviewed bulk migration entry point`() {
        SwingUtilities.invokeAndWait {
            var selectedSkills = 0
            val panel = SkillsPanel(
                requestContext = {},
                openSkill = {},
                revealSkill = {},
                migrateDuplicates = { skills, _ -> selectedSkills = skills.size },
                agentIcon = { null },
            )
            panel.setProjects(emptyList())
            val sample = SkillBrowserTest.sampleSnapshot()
            val skill = sample.skills.single()
            val duplicate = skill.copy(
                consistency = com.shutterstar.agenthub.environment.skills.model.SkillConsistency.IDENTICAL,
                sources = listOf(
                    skill.sources.last().copy(agentId = "claude", shared = false, path = "C:/Users/example/.claude/skills/php-review"),
                    skill.sources.last().copy(agentId = "codex", shared = false, path = "C:/Users/example/.codex/skills/php-review"),
                ),
            )
            panel.showState(SkillBrowserState(sample.context, sample.copy(skills = listOf(duplicate))))

            button(panel, "Migrate duplicates…").doClick()

            assertEquals(1, selectedSkills)
        }
    }

    @Test fun `refreshPreferring selects the shared row once the refreshed snapshot arrives, surviving an unrelated render in between`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel(requestContext = {}, openSkill = {}, revealSkill = {}, agentIcon = { null })
            panel.setProjects(emptyList())
            val snapshot = SkillBrowserTest.sampleSnapshot()
            panel.showState(SkillBrowserState(snapshot.context, snapshot))

            // Select the non-shared (agent-specific) occurrence, as if the user were about to
            // promote it.
            panel.setQuery(".claude")
            assertEquals(1, panel.occurrenceCount)
            val beforePromote = panel.selectedOccurrence!!
            assertTrue(!beforePromote.source.shared)
            val skillId = beforePromote.skill.identity.id

            panel.refreshPreferring { it.skill.identity.id == skillId && it.source.shared }
            // An unrelated UI event (e.g. clearing the query) re-renders before the refreshed
            // snapshot arrives; the pending preference must not be consumed by it.
            panel.setQuery("")
            panel.showState(SkillBrowserState(snapshot.context, snapshot))

            assertTrue(panel.selectedOccurrence!!.source.shared)
        }
    }

    @Test fun `refreshPreferring survives the synchronous loading callback the real controller fires first`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel(requestContext = {}, openSkill = {}, revealSkill = {}, agentIcon = { null })
            panel.setProjects(emptyList())
            val after = SkillBrowserTest.sampleSnapshot()
            val skill = after.skills.single()
            // Before promoting, only the agent-specific source exists yet - as in the real app.
            val before = after.copy(skills = listOf(skill.copy(sources = skill.sources.filterNot { it.shared })))

            panel.showState(SkillBrowserState(before.context, before))
            assertEquals(1, panel.occurrenceCount)
            assertTrue(!panel.selectedOccurrence!!.source.shared)
            val skillId = panel.selectedOccurrence!!.skill.identity.id

            panel.refreshPreferring { it.skill.identity.id == skillId && it.source.shared }
            // SkillBrowserController.refresh() synchronously delivers a "loading" state carrying
            // the stale cached (pre-promote) snapshot before the real, background-discovered
            // (post-promote) one arrives. The pending preference must survive that intermediate
            // call and only apply once the actual result shows up.
            panel.showState(SkillBrowserState(before.context, before, loading = true))
            panel.showState(SkillBrowserState(after.context, after))

            assertTrue(panel.selectedOccurrence!!.source.shared)
        }
    }

    @Test fun `should render wide and compact views without shrinking or losing selection`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel({}, {}, {}, agentIcon = { null })
            panel.setProjects(emptyList())
            // A renderer's validate() needs a displayable parent, as it has in the actual IDE.
            panel.addNotify()
            val snapshot = SkillBrowserTest.sampleSnapshot()
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            val selected = panel.selectedOccurrence!!.key
            repeat(3) {
                resize(panel, 1000, 680)
                assertEquals(selected, panel.selectedOccurrence!!.key)
                if (it == 0) screenshot(panel, "skills-wide.png")
                resize(panel, 480, 680)
                assertEquals(selected, panel.selectedOccurrence!!.key)
                if (it == 0) screenshot(panel, "skills-compact.png")
            }
            assertTrue(panel.width == 480)
            panel.removeNotify()
        }
    }

    @Test fun `the project filter defaults to All projects, ahead of any specific project`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel({}, {}, {}, agentIcon = { null })
            val first = com.shutterstar.agenthub.projects.model.DiscoveredProject(
                com.shutterstar.agenthub.projects.model.ProjectIdentity("a", "K:/a", null, null), "Alpha", "K:/a", null, null, null, emptyList(), null,
            )
            val second = com.shutterstar.agenthub.projects.model.DiscoveredProject(
                com.shutterstar.agenthub.projects.model.ProjectIdentity("b", "K:/b", null, null), "Beta", "K:/b", null, null, null, emptyList(), null,
            )

            panel.setProjects(listOf(second, first))

            assertEquals(null, panel.selectedProject)
        }
    }

    @Test fun `showState folds linked occurrences into the shared row using the snapshot's own sourceStats`() {
        SwingUtilities.invokeAndWait {
            val panel = SkillsPanel({}, {}, {}, agentIcon = { null })
            panel.setProjects(emptyList())
            val shared = com.shutterstar.agenthub.environment.skills.model.SkillSource(
                null, "/home/user/.agents/skills/humanizer", com.shutterstar.agenthub.environment.skills.model.SkillScope.GLOBAL, shared = true, fingerprint = "fp",
            )
            val links = listOf("claude", "codex").map { agentId ->
                com.shutterstar.agenthub.environment.skills.model.SkillSource(
                    agentId, "/home/user/.$agentId/skills/humanizer", com.shutterstar.agenthub.environment.skills.model.SkillScope.GLOBAL, shared = false, fingerprint = "fp",
                )
            }
            val skill = com.shutterstar.agenthub.environment.skills.model.AgentSkill(
                com.shutterstar.agenthub.environment.skills.model.SkillIdentity("humanizer"), "humanizer", null,
                com.shutterstar.agenthub.environment.skills.model.SkillScope.GLOBAL, listOf(shared) + links, setOf("claude", "codex"),
                com.shutterstar.agenthub.environment.skills.model.SkillConsistency.IDENTICAL,
            )
            val sourceStats = links.associate { it.path to SourceStat(1, 10, isLink = true) }
            val snapshot = SkillBrowserSnapshot(
                SkillBrowserContext(com.shutterstar.agenthub.environment.skills.model.SkillScope.GLOBAL), listOf(skill), sourceStats = sourceStats,
            )

            panel.showState(SkillBrowserState(snapshot.context, snapshot))

            assertEquals(1, panel.occurrenceCount)
            assertEquals(setOf("claude", "codex"), panel.selectedOccurrence!!.agentIds)
        }
    }

    @Test fun `Overview lists every file, not just scripts, and Additional sources excludes the viewed source`() {
        SwingUtilities.invokeAndWait {
            val opened = mutableListOf<Path>()
            val conflictsResolved = mutableListOf<Pair<SkillOccurrenceRow, VersionSides>>()
            val panel = SkillsPanel(
                {}, opened::add, {},
                resolveVersions = { row, sides -> conflictsResolved.add(row to sides) },
                syncTargetIds = { setOf("claude", "codex") },
                agentIcon = { null },
            )
            panel.setProjects(emptyList())
            val shared = com.shutterstar.agenthub.environment.skills.model.SkillSource(
                null, "/home/user/.agents/skills/humanizer", com.shutterstar.agenthub.environment.skills.model.SkillScope.GLOBAL, shared = true, fingerprint = "fp",
            )
            val independentCopy = com.shutterstar.agenthub.environment.skills.model.SkillSource(
                "claude", "/home/user/.claude/skills/humanizer", com.shutterstar.agenthub.environment.skills.model.SkillScope.GLOBAL, shared = false, fingerprint = "fp2",
            )
            val identicalCopy = com.shutterstar.agenthub.environment.skills.model.SkillSource(
                "codex", "/home/user/.codex/skills/humanizer", com.shutterstar.agenthub.environment.skills.model.SkillScope.GLOBAL, shared = false, fingerprint = "fp",
            )
            val systemCopy = com.shutterstar.agenthub.environment.skills.model.SkillSource(
                "copilot", "/home/user/.copilot/skills/humanizer", com.shutterstar.agenthub.environment.skills.model.SkillScope.GLOBAL,
                shared = false, fingerprint = "fp", system = true,
            )
            val skill = com.shutterstar.agenthub.environment.skills.model.AgentSkill(
                com.shutterstar.agenthub.environment.skills.model.SkillIdentity("humanizer"), "humanizer", null,
                com.shutterstar.agenthub.environment.skills.model.SkillScope.GLOBAL, listOf(shared, independentCopy, identicalCopy, systemCopy),
                setOf("claude", "codex", "copilot"),
                com.shutterstar.agenthub.environment.skills.model.SkillConsistency.DIFFERENT,
            )
            val snapshot = SkillBrowserSnapshot(
                SkillBrowserContext(com.shutterstar.agenthub.environment.skills.model.SkillScope.GLOBAL), listOf(skill),
                sourceFiles = mapOf(shared.path to listOf("SKILL.md", "reference.md", "assets/logo.png")),
                sourceStats = mapOf(
                    shared.path to SourceStat(3, 21_504, isLink = false),
                    independentCopy.path to SourceStat(2, 1_024, isLink = false),
                ),
            )
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            assertEquals(shared.path, panel.selectedOccurrence!!.source.path)

            val texts = descendants(panel).mapNotNull { component ->
                when (component) {
                    is JTextArea -> component.text
                    is JBLabel -> component.text
                    else -> null
                }
            }
            val headers = descendants(panel).filterIsInstance<JBLabel>().map { it.text }

            // "reference.md" and "assets/logo.png" are not scripts - they must still be listed.
            assertTrue(texts.containsAll(listOf(".\\SKILL.md", ".\\reference.md", ".\\assets\\logo.png")))
            assertTrue(texts.contains("3 files, ${com.shutterstar.agenthub.projects.ui.AgentHubUiFormat.formatSize(21_504)}"))
            val fileRow = descendants(panel).filterIsInstance<JBLabel>().first { it.text == ".\\assets\\logo.png" }
            assertEquals(com.intellij.util.ui.JBUI.CurrentTheme.Link.Foreground.ENABLED, fileRow.foreground)
            assertEquals("Open ${Path.of(shared.path).resolve("assets/logo.png").normalize()}", fileRow.accessibleContext.accessibleName)
            assertTrue(fileRow.inputMap.get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)) != null)
            fileRow.dispatchEvent(MouseEvent(fileRow, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 4, 4, 1, false, MouseEvent.BUTTON1))
            assertTrue(opened.isEmpty())
            fileRow.dispatchEvent(MouseEvent(fileRow, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 4, 4, 2, false, MouseEvent.BUTTON1))
            assertEquals(listOf(Path.of(shared.path).resolve("assets/logo.png").normalize()), opened)
            assertTrue(headers.any { it == "Files (scripts are never run by AgentHub)" })
            // The independent copy is a genuine additional source; the shared row's own path
            // (already shown under "Location") must not be repeated here.
            assertTrue(headers.any { it == "Additional sources" })
            // shared.path is shown exactly once, under "Location" - not a second time here.
            assertEquals(1, texts.count { it == shared.path })
            assertTrue(texts.any { it.contains(independentCopy.path) })
            assertTrue(texts.any { it.contains(identicalCopy.path) })
            assertTrue(texts.any { it.contains(systemCopy.path) })
            assertEquals(4, descendants(panel).filterIsInstance<javax.swing.JSeparator>().size)
            val separator = descendants(panel).filterIsInstance<javax.swing.JSeparator>().first()
            val divider = separator.parent as JPanel
            val dividerInsets = divider.border.getBorderInsets(divider)
            assertEquals(JBUIScale.scale(10), dividerInsets.top)
            assertEquals(dividerInsets.top, dividerInsets.bottom)
            assertEquals(separator.preferredSize.height + dividerInsets.top + dividerInsets.bottom, divider.preferredSize.height)
            assertTrue(headers.containsAll(listOf("Claude Code", "Codex CLI", "Copilot CLI", "· Agent-specific", "· System")))
            val sourcePathLabel = descendants(panel).filterIsInstance<JBLabel>()
                .first { it.text == independentCopy.path }
            assertEquals(independentCopy.path, sourcePathLabel.toolTipText)
            assertTrue(texts.contains("2 files, ${com.shutterstar.agenthub.projects.ui.AgentHubUiFormat.formatSize(1_024)}"))
            assertEquals(com.intellij.ui.JBColor.GRAY, sourcePathLabel.foreground)
            val resolveButtons = descendants(panel).filterIsInstance<JButton>().filter { it.text == "Resolve Conflict…" }
            assertEquals(1, resolveButtons.size)
            resolveButtons.single().doClick()
            assertEquals(listOf(true to listOf("claude")), conflictsResolved.map { it.second.current.shared to it.second.others.map { other -> other.agentId } })
        }
    }

    @Test fun `the Agents tab groups direct shared readers and offers Start Sharing for an unshared agent`() {
        SwingUtilities.invokeAndWait {
            val started = mutableListOf<String>()
            val panel = SkillsPanel(
                requestContext = {},
                openSkill = {},
                revealSkill = {},
                startSharingSkill = { _, agentId -> started += agentId },
                syncTargetIds = { setOf("claude") },
                agentIcon = { null },
            )
            panel.setProjects(emptyList())
            val sample = SkillBrowserTest.sampleSnapshot()
            val skillId = sample.skills.single().identity.id
            val statuses = listOf(
                com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget(
                    "codex", Path.of("/home/user/.agents/skills/php-review"),
                    com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus.NATIVE,
                ),
                com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget(
                    "copilot", Path.of("/home/user/.agents/skills/php-review"),
                    com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus.NATIVE,
                ),
                com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget(
                    "claude", Path.of("/home/user/.claude/skills/php-review"),
                    com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus.NOT_AVAILABLE,
                ),
            )
            val snapshot = sample.copy(targetStatuses = mapOf(skillId to statuses))
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            panel.setQuery(".agents")

            val labels = descendants(panel).filterIsInstance<JBLabel>().map { it.text }
            assertTrue(labels.contains("Global shared skills"))
            assertTrue(labels.indexOf("Global shared skills") < labels.indexOf("Agent coverage"))
            assertTrue(labels.contains(Path.of(sample.skills.single().sources.first { it.shared }.path).parent.toString()))
            assertFalse(labels.contains("Other agents"))
            assertTrue(descendants(panel).filterIsInstance<javax.swing.JSeparator>().size >= 2)
            assertTrue(descendants(panel).filterIsInstance<JPanel>().any { group ->
                group.layout is javax.swing.BoxLayout &&
                    group.components.filterIsInstance<JLabel>().map { it.text } == listOf("Codex CLI", "Copilot CLI")
            })
            assertEquals(1, descendants(panel).filterIsInstance<JButton>().count { it.text == "Start Sharing" })
            button(panel, "Start Sharing").doClick()
            assertEquals(listOf("claude"), started)
        }
    }

    @Test fun `project environment can navigate to a global skill`() {
        SwingUtilities.invokeAndWait {
            val requested = mutableListOf<SkillBrowserContext>()
            val panel = SkillsPanel(requested::add, {}, {}, agentIcon = { null })
            val snapshot = SkillBrowserTest.sampleSnapshot()
            val source = snapshot.skills.single().sources.first()

            panel.navigateTo(snapshot.context, snapshot.skills.single().identity.id, source.path)
            panel.showState(SkillBrowserState(snapshot.context, snapshot))

            assertEquals(snapshot.context, requested.last())
            assertEquals(source.path, panel.selectedOccurrence?.source?.path)
        }
    }
    @Test fun `environment navigation skips All projects and opens the selected skill in compact mode`() {
        SwingUtilities.invokeAndWait {
            val requested = mutableListOf<SkillBrowserContext>()
            val panel = SkillsPanel(requested::add, {}, {}, agentIcon = { null })
            val project = com.shutterstar.agenthub.projects.model.DiscoveredProject(
                com.shutterstar.agenthub.projects.model.ProjectIdentity("p", "K:/project", null, null),
                "Project", "K:/project", null, null, null, emptyList(), null,
            )
            val context = SkillBrowserContext(com.shutterstar.agenthub.environment.skills.model.SkillScope.PROJECT, project)
            val source = com.shutterstar.agenthub.environment.skills.model.SkillSource(
                "codex", "K:/project/.codex/skills/review", context.scope, false, "fp",
            )
            val skill = com.shutterstar.agenthub.environment.skills.model.AgentSkill(
                com.shutterstar.agenthub.environment.skills.model.SkillIdentity("review"), "review", null,
                context.scope, listOf(source), setOf("codex"),
                com.shutterstar.agenthub.environment.skills.model.SkillConsistency.SINGLE_SOURCE,
            )
            val snapshot = SkillBrowserSnapshot(context, listOf(skill))
            panel.setProjects(listOf(project))
            panel.addNotify()
            resize(panel, 480, 680)
            var detailsOnly = false
            panel.onDetailsOnlyChanged = { detailsOnly = it }

            panel.navigateTo(context, "review", source.path)
            assertEquals(project, panel.selectedProject)
            assertTrue(descendants(panel).filterIsInstance<JComboBox<*>>().first { it.accessibleContext.accessibleName == "Skill project" }.isVisible)
            assertEquals(context, requested.last())
            panel.showState(SkillBrowserState(context, snapshot, loading = true))
            assertFalse(detailsOnly)
            panel.showState(SkillBrowserState(context, snapshot))

            assertEquals(source.path, panel.selectedOccurrence?.source?.path)
            assertTrue(detailsOnly)
            button(panel, "Skills").doClick()
            assertFalse(detailsOnly)
            assertEquals(null, panel.selectedProject)
            assertEquals(SkillBrowserContext(context.scope), requested.last())
            panel.removeNotify()
        }
    }

    @Test fun `managed copy drift is distinct from broken targets and sync actions reflect observed state`() = SwingUtilities.invokeAndWait {
        val panel = SkillsPanel({}, {}, {}, managedTargetIds = { setOf("claude") }, agentIcon = { null })
        val original = SkillBrowserTest.sampleSnapshot()
        val skillId = original.skills.single().identity.id
        fun show(status: com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus, fingerprint: String?) {
            val target = com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget(
                "claude", Path.of("fixture/skill"), status, fingerprint = fingerprint,
            )
            val snapshot = original.copy(targetStatuses = mapOf(skillId to listOf(target)))
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
        }
        show(com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus.COPIED, "B")
        assertTrue(panel.summaryText.contains("1 out of sync"))
        assertFalse(panel.summaryText.contains("broken"))
        assertTrue(button(panel, "Resync").isEnabled)
        show(com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus.COPIED, "A")
        assertTrue(panel.summaryText.contains("1 healthy"))
        assertFalse(button(panel, "In sync").isEnabled)
        show(com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus.BROKEN_LINK, null)
        assertTrue(panel.summaryText.contains("1 broken"))
        assertTrue(button(panel, "Repair").isEnabled)
        val list = descendants(panel).filterIsInstance<javax.swing.JList<*>>().single()
        assertTrue(list.actionMap.get(com.shutterstar.agenthub.projects.ui.RowContextMenus.ACTION) != null)
    }

    @Test fun `right and middle clicks do not navigate or open a skill`() {
        SwingUtilities.invokeAndWait {
            val opened = mutableListOf<Path>()
            val panel = SkillsPanel({}, { opened.add(it) }, {}, agentIcon = { null })
            val snapshot = SkillBrowserTest.sampleSnapshot()
            panel.showState(SkillBrowserState(snapshot.context, snapshot))
            resize(panel, 400, 680)
            panel.showDefaultContent()
            var detailsOnly = false
            panel.onDetailsOnlyChanged = { detailsOnly = it }
            val list = descendants(panel).filterIsInstance<javax.swing.JList<*>>().first()
            val rect = list.getCellBounds(0, 0)
            for (mouseButton in listOf(MouseEvent.BUTTON2, MouseEvent.BUTTON3)) {
                for (count in listOf(1, 2)) {
                    val event = MouseEvent(list, MouseEvent.MOUSE_CLICKED, 0, 0, rect.x + 5, rect.y + 5, count, false, mouseButton)
                    list.mouseListeners.forEach { it.mouseClicked(event) }
                }
            }
            assertFalse(detailsOnly)
            assertTrue(opened.isEmpty())
            val event = MouseEvent(list, MouseEvent.MOUSE_CLICKED, 0, 0, rect.x + 5, rect.y + 5, 1, false, MouseEvent.BUTTON1)
            list.mouseListeners.forEach { it.mouseClicked(event) }
            assertTrue(detailsOnly)
        }
    }
    private fun tabTitles(panel: SkillsPanel): List<String> =
        descendants(panel).filterIsInstance<LeftAlignedTabbedPane>().single().let { pane ->
            val tabs = descendants(pane).filterIsInstance<javax.swing.JTabbedPane>().single()
            (0 until tabs.tabCount).map(tabs::getTitleAt)
        }

    private fun button(root: Container, label: String): JButton = descendants(root).filterIsInstance<JButton>().first { it.text == label || it.accessibleContext.accessibleName == label }

    private fun resize(panel: SkillsPanel, width: Int, height: Int) {
        panel.setSize(width, height)
        panel.dispatchEvent(ComponentEvent(panel, ComponentEvent.COMPONENT_RESIZED))
        fun layout(container: Container) {
            container.doLayout()
            container.components.filterIsInstance<Container>().forEach(::layout)
        }
        layout(panel)
        // doLayout() reassigns child bounds directly, bypassing the AWT event queue that would
        // normally deliver COMPONENT_RESIZED to ResponsiveMasterDetail's internal listener in a
        // real window. Dispatch it by hand so this offscreen test exercises the same wide/compact
        // switch a live IDE resize would.
        descendants(panel).forEach { it.dispatchEvent(ComponentEvent(it, ComponentEvent.COMPONENT_RESIZED)) }
        // That listener swaps children (removeAll/add) and calls revalidate(), which only
        // schedules a layout via RepaintManager instead of running one inline. Lay out again so
        // the swapped-in components have real bounds before the screenshot is taken.
        layout(panel)
    }

    private fun screenshot(panel: SkillsPanel, name: String) {
        val directory = System.getProperty("agenthub.ui.screenshot.dir") ?: return
        Files.createDirectories(Path.of(directory))
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try { panel.printAll(graphics) } finally { graphics.dispose() }
        ImageIO.write(image, "png", Path.of(directory).resolve(name).toFile())
    }

    companion object {
        @JvmStatic @BeforeAll fun setupScaling() { JBUIScale.setSystemScaleFactor(1.0f) }
    }
}
