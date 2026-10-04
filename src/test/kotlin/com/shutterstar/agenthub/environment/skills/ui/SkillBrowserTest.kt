package com.shutterstar.agenthub.environment.skills.ui

import com.shutterstar.agenthub.writeSkillMd
import com.shutterstar.agenthub.environment.skills.discovery.ClaudeSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SharedSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SkillDiscoveryService
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor

class SkillBrowserTest {
    @TempDir lateinit var root: Path

    @Test fun `should report file count and size per source, and flag a linked source without following it into the wrong stat`() {
        val home = Files.createDirectories(root.resolve("home"))
        val canonical = home.resolve(".agents/skills/php-review")
        writeSkillMd(canonical, "# Review\nSkill description")
        Files.writeString(canonical.resolve("extra.md"), "reference content")
        val claudeSkills = Files.createDirectories(home.resolve(".claude/skills"))
        val linked = claudeSkills.resolve("php-review")
        val linkCreated = runCatching { Files.createSymbolicLink(linked, canonical) }.isSuccess
        assumeTrue(linkCreated, "symlink creation requires elevated privilege on this machine")
        val discovery = SkillBrowserDiscovery(SkillDiscoveryService(listOf(SharedSkillProvider(home), ClaudeSkillProvider(home))))

        val snapshot = discovery.discover(SkillBrowserContext(SkillScope.GLOBAL))

        val canonicalStat = snapshot.sourceStats.getValue(canonical.toAbsolutePath().normalize().toString())
        assertEquals(2, canonicalStat.fileCount)
        assertEquals(Files.size(canonical.resolve("SKILL.md")) + Files.size(canonical.resolve("extra.md")), canonicalStat.totalSizeBytes)
        assertFalse(canonicalStat.isLink)

        val linkedStat = snapshot.sourceStats.getValue(linked.toAbsolutePath().normalize().toString())
        assertTrue(linkedStat.isLink)
        // The link is transparently walked, so its stat reflects the target's real content - not zero.
        assertEquals(canonicalStat.fileCount, linkedStat.fileCount)
        assertEquals(canonicalStat.totalSizeBytes, linkedStat.totalSizeBytes)
    }

    @Test fun `sourceFiles lists every file under a source, not just scripts`() {
        val home = Files.createDirectories(root.resolve("home"))
        val skillDir = home.resolve(".agents/skills/humanizer")
        writeSkillMd(skillDir, "# Review\nSkill description")
        Files.writeString(skillDir.resolve("reference.md"), "notes")
        Files.createDirectories(skillDir.resolve("assets"))
        Files.writeString(skillDir.resolve("assets").resolve("logo.svg"), "<svg/>")
        val discovery = SkillBrowserDiscovery(SkillDiscoveryService(listOf(SharedSkillProvider(home))))

        val snapshot = discovery.discover(SkillBrowserContext(SkillScope.GLOBAL))

        val files = snapshot.sourceFiles.getValue(skillDir.toAbsolutePath().normalize().toString())
        assertTrue(files.contains("SKILL.md"))
        assertTrue(files.contains("reference.md"))
        assertTrue(files.any { it.endsWith("logo.svg") })
    }

    @Test fun `source statistics flag files beyond the scan depth`() {
        val home = Files.createDirectories(root.resolve("home"))
        val skillDir = home.resolve(".agents/skills/deep-skill")
        writeSkillMd(skillDir, "# Review\nSkill description")
        var nested = skillDir
        repeat(12) { nested = Files.createDirectories(nested.resolve("level$it")) }
        Files.writeString(nested.resolve("hidden.txt"), "content")
        val discovery = SkillBrowserDiscovery(SkillDiscoveryService(listOf(SharedSkillProvider(home))))

        val snapshot = discovery.discover(SkillBrowserContext(SkillScope.GLOBAL))

        val stat = snapshot.sourceStats.getValue(skillDir.toAbsolutePath().normalize().toString())
        assertTrue(stat.truncated)
        assertEquals(1, stat.fileCount)
    }

    @Test fun `sourceLabel prefers System over Shared source for a vendor-shipped or synced skill`() {
        val systemSource = SkillSource("claude", "/home/user/.claude/skills/synced/bucket/pdf", SkillScope.GLOBAL, shared = false, fingerprint = "fp", system = true)
        val skill = AgentSkill(SkillIdentity("pdf"), "pdf", null, SkillScope.GLOBAL, listOf(systemSource), setOf("claude"), SkillConsistency.SINGLE_SOURCE)
        val row = SkillBrowserModel.rows(SkillBrowserSnapshot(SkillBrowserContext(SkillScope.GLOBAL), listOf(skill))).single()

        assertEquals("System", row.sourceLabel)
    }

    @Test fun `System filter isolates vendor-shipped occurrences, and they sort after everything else`() {
        val systemSource = SkillSource("claude", "/home/user/.claude/skills/synced/bucket/aaa-system", SkillScope.GLOBAL, shared = false, fingerprint = "fp", system = true)
        val systemSkill = AgentSkill(SkillIdentity("aaa-system"), "aaa-system", null, SkillScope.GLOBAL, listOf(systemSource), setOf("claude"), SkillConsistency.SINGLE_SOURCE)
        val ownSource = SkillSource("claude", "/home/user/.claude/skills/zzz-own", SkillScope.GLOBAL, shared = false, fingerprint = "fp")
        val ownSkill = AgentSkill(SkillIdentity("zzz-own"), "zzz-own", null, SkillScope.GLOBAL, listOf(ownSource), setOf("claude"), SkillConsistency.SINGLE_SOURCE)
        val rows = SkillBrowserModel.rows(SkillBrowserContext(SkillScope.GLOBAL), listOf(systemSkill, ownSkill))

        // "aaa-system" would sort first alphabetically, but system occurrences always sort last.
        assertEquals(listOf("zzz-own", "aaa-system"), rows.map { it.title })
        assertEquals(listOf("aaa-system"), SkillBrowserModel.filter(rows, "", SkillBrowserFilter.SYSTEM, null).map { it.title })
    }

    @Test fun `should discover project skills without sessions and keep global scope separate`() {
        val home = Files.createDirectories(root.resolve("home"))
        val project = project(root.resolve("project"))
        writeSkillMd(home.resolve(".agents/skills/global-skill"), "# Review\nSkill description")
        writeSkillMd(Path.of(project.path!!).resolve(".claude/skills/project-skill"), "# Review\nSkill description")
        val discovery = SkillBrowserDiscovery(SkillDiscoveryService(listOf(SharedSkillProvider(home), ClaudeSkillProvider(home))))

        val global = discovery.discover(SkillBrowserContext(SkillScope.GLOBAL))
        val local = discovery.discover(SkillBrowserContext(SkillScope.PROJECT, project))

        assertEquals(listOf("global-skill"), global.skills.map { it.name })
        assertEquals(listOf("project-skill"), local.skills.map { it.name })
        assertTrue(project.agents.isEmpty())
    }

    @Test fun `a null-project context aggregates every project with skills, keeping each row's own project for mutations`() {
        val home = Files.createDirectories(root.resolve("home"))
        val first = project(root.resolve("project-a"))
        val second = project(root.resolve("project-b"))
        writeSkillMd(Path.of(first.path!!).resolve(".claude/skills/skill-a"), "# Review\nSkill description")
        writeSkillMd(Path.of(second.path!!).resolve(".claude/skills/skill-b"), "# Review\nSkill description")
        val discovery = SkillBrowserDiscovery(
            SkillDiscoveryService(listOf(SharedSkillProvider(home), ClaudeSkillProvider(home))),
            projectsWithSkills = { listOf(first, second) },
        )

        val all = discovery.discover(SkillBrowserContext(SkillScope.PROJECT))

        assertEquals(setOf("skill-a", "skill-b"), all.skills.map { it.name }.toSet())
        assertEquals(2, all.rows.size)
        // Each row keeps the concrete project it was actually discovered under - never the null
        // "all projects" request - so Share/Promote/Resync always have a real project to act on.
        assertEquals(setOf(first.path, second.path), all.rows.map { it.context.project?.path }.toSet())
        assertTrue(all.rows.none { it.context.project == null })
    }

    @Test fun `linked agent-specific occurrences fold into the shared row instead of listing one row per agent`() {
        val shared = SkillSource(null, "/home/user/.agents/skills/humanizer", SkillScope.GLOBAL, shared = true, fingerprint = "fp")
        val links = listOf("claude", "codex", "cursor").map { agentId ->
            SkillSource(agentId, "/home/user/.$agentId/skills/humanizer", SkillScope.GLOBAL, shared = false, fingerprint = "fp")
        }
        val skill = AgentSkill(SkillIdentity("humanizer"), "humanizer", null, SkillScope.GLOBAL, listOf(shared) + links, setOf("claude", "codex", "cursor"), SkillConsistency.IDENTICAL)
        val rows = SkillBrowserModel.rows(SkillBrowserContext(SkillScope.GLOBAL), listOf(skill))
        assertEquals(4, rows.size)

        val merged = SkillBrowserModel.mergeLinkedOccurrences(rows) { path -> path != shared.path }

        assertEquals(1, merged.size)
        assertEquals(shared.path, merged.single().source.path)
        assertEquals(setOf("claude", "codex", "cursor"), merged.single().agentIds)
    }

    @Test fun `a genuinely independent copy keeps its own row and its agent id is not duplicated onto the shared row`() {
        val shared = SkillSource(null, "/home/user/.agents/skills/humanizer", SkillScope.GLOBAL, shared = true, fingerprint = "fp")
        val independentCopy = SkillSource("claude", "/home/user/.claude/skills/humanizer", SkillScope.GLOBAL, shared = false, fingerprint = "fp2")
        val linked = SkillSource("codex", "/home/user/.codex/skills/humanizer", SkillScope.GLOBAL, shared = false, fingerprint = "fp")
        val skill = AgentSkill(SkillIdentity("humanizer"), "humanizer", null, SkillScope.GLOBAL, listOf(shared, independentCopy, linked), setOf("claude", "codex"), SkillConsistency.DIFFERENT)
        val rows = SkillBrowserModel.rows(SkillBrowserContext(SkillScope.GLOBAL), listOf(skill))

        val merged = SkillBrowserModel.mergeLinkedOccurrences(rows) { path -> path == linked.path }

        assertEquals(2, merged.size)
        assertEquals(setOf("codex"), merged.first { it.source.path == shared.path }.agentIds)
        assertEquals(setOf("claude"), merged.first { it.source.path == independentCopy.path }.agentIds)
    }

    @Test fun `should preserve source occurrences and use H1 without metadata in the title`() {
        val snapshot = sampleSnapshot()
        val rows = SkillBrowserModel.rows(snapshot)
        assertEquals(2, rows.size)
        assertEquals(listOf("PHP Review", "PHP Review"), rows.map { it.title })
        assertNotEquals(rows[0].key, rows[1].key)
        assertEquals(1, SkillBrowserModel.filter(rows, "", SkillBrowserFilter.SHARED, null).size)
        assertEquals(1, SkillBrowserModel.filter(rows, "PHP", SkillBrowserFilter.ALL, "claude").size)
        assertEquals(2, SkillBrowserModel.filter(rows, "review", SkillBrowserFilter.CONFLICTS, null).size)
        assertTrue(SkillBrowserModel.filter(rows, "absent", SkillBrowserFilter.ALL, null).isEmpty())
    }

    @Test fun `ownership filter keeps only the rows the caller reports as AgentHub-managed`() {
        val rows = SkillBrowserModel.rows(sampleSnapshot())
        val managedPath = rows.first { !it.source.shared }.source.path
        val isManaged: (SkillOccurrenceRow) -> Boolean = { it.source.path == managedPath }

        val managedOnly = SkillBrowserModel.filter(rows, "", SkillBrowserFilter.ALL, null, SkillOwnershipFilter.MANAGED, isManaged)
        val unmanagedOnly = SkillBrowserModel.filter(rows, "", SkillBrowserFilter.ALL, null, SkillOwnershipFilter.UNMANAGED, isManaged)
        val all = SkillBrowserModel.filter(rows, "", SkillBrowserFilter.ALL, null, SkillOwnershipFilter.ALL, isManaged)

        assertEquals(listOf(managedPath), managedOnly.map { it.source.path })
        assertEquals(rows.size - 1, unmanagedOnly.size)
        assertEquals(rows.size, all.size)
        // The shared/canonical source has no agentId of its own, so it can never read as
        // AgentHub-managed here - it always falls into the UNMANAGED bucket.
        assertTrue(unmanagedOnly.any { it.source.shared })
    }

    @Test fun `dashboardSummary counts shared, in-sync and conflicting skill groups, not occurrences`() {
        fun skill(id: String, consistency: SkillConsistency, shared: Boolean) = AgentSkill(
            SkillIdentity(id),
            id,
            null,
            SkillScope.GLOBAL,
            listOf(SkillSource(if (shared) null else "claude", "/path/$id", SkillScope.GLOBAL, shared, "fp")),
            emptySet(),
            consistency,
        )
        val skills = listOf(
            skill("a", SkillConsistency.IDENTICAL, shared = true),
            skill("b", SkillConsistency.IDENTICAL, shared = true),
            skill("c", SkillConsistency.DIFFERENT, shared = true),
            skill("d", SkillConsistency.SINGLE_SOURCE, shared = false),
        )

        val summary = SkillBrowserModel.dashboardSummary(skills)

        assertEquals(3, summary.sharedSkillCount)
        assertEquals(2, summary.inSyncCount)
        assertEquals(1, summary.conflictCount)
        assertEquals("3 shared · 2 in sync · 1 conflict", summary.label)
        assertEquals("2 in sync", SkillDashboardSummary(0, 2, 0).label)
        assertEquals("", SkillDashboardSummary(0, 0, 0).label)
    }

    @Test fun `should distinguish physical projects with equal logical identities`() {
        val first = project(root.resolve("checkout-a"))
        val second = project(root.resolve("checkout-b"))
        val a = SkillBrowserContext(SkillScope.PROJECT, first)
        val b = SkillBrowserContext(SkillScope.PROJECT, second)
        assertEquals(first.identity, second.identity)
        assertNotEquals(a.key, b.key)
    }

    @Test fun `should combine discovering agent icons only for the same physical source`() {
        val snapshot = sampleSnapshot()
        val skill = snapshot.skills.single()
        val source = skill.sources.last()
        val rows = SkillBrowserModel.rows(snapshot.copy(skills = listOf(skill.copy(sources = skill.sources + source.copy(agentId = "cursor")))))
        assertEquals(2, rows.size)
        assertEquals(setOf("claude", "cursor"), rows.first { !it.source.shared }.agentIds)
    }

    @Test fun `should reject late results after switching scope`() {
        val workers = QueueExecutor()
        val deliveries = mutableListOf<() -> Unit>()
        val states = mutableListOf<SkillBrowserState>()
        val controller = SkillBrowserController(workers, { deliveries.add(it) }, { SkillBrowserSnapshot(it, emptyList()) }, states::add)
        val global = SkillBrowserContext(SkillScope.GLOBAL)
        val local = SkillBrowserContext(SkillScope.PROJECT, project(root.resolve("project")))
        controller.refresh(global)
        workers.next()
        controller.refresh(local)
        deliveries.removeAt(0)()
        assertEquals(local, states.last().context)
        assertTrue(states.last().loading)
        workers.next()
        deliveries.removeAt(0)()
        assertEquals(local, states.last().snapshot!!.context)
        assertFalse(states.last().loading)
    }

    @Test fun `should ignore callbacks after disposal and avoid exposing exception contents`() {
        val workers = QueueExecutor()
        val deliveries = mutableListOf<() -> Unit>()
        val states = mutableListOf<SkillBrowserState>()
        val controller = SkillBrowserController(workers, { deliveries.add(it) }, { throw IllegalStateException("secret fixture") }, states::add)
        controller.refresh(SkillBrowserContext(SkillScope.GLOBAL))
        workers.next()
        deliveries.removeAt(0)()
        assertTrue(states.last().error!!.contains("IllegalStateException"))
        assertFalse(states.last().error!!.contains("secret"))
        controller.refresh(SkillBrowserContext(SkillScope.GLOBAL))
        workers.next()
        val count = states.size
        controller.close()
        deliveries.removeAt(0)()
        assertEquals(count, states.size)
    }

    private fun project(path: Path): DiscoveredProject {
        Files.createDirectories(path)
        return DiscoveredProject(ProjectIdentity("same-remote", null, null, null), "AgentHub", path.toString(), null, null, null, emptyList(), null)
    }

    private class QueueExecutor : Executor {
        private val work = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { work.add(command) }
        fun next() = work.removeAt(0).run()
    }

    internal companion object {
        fun sampleSnapshot(): SkillBrowserSnapshot {
            val sources = listOf(
                SkillSource(null, "C:/Users/example/.agents/skills/php-review", SkillScope.GLOBAL, true, "A", "PHP Review"),
                SkillSource("claude", "C:/Users/example/.claude/skills/php-review", SkillScope.GLOBAL, false, "B", "PHP Review"),
            )
            val skill = AgentSkill(SkillIdentity("php-review"), "php-review", "Review PHP code and explain actionable findings.", SkillScope.GLOBAL, sources, setOf("claude"), SkillConsistency.DIFFERENT)
            return SkillBrowserSnapshot(SkillBrowserContext(SkillScope.GLOBAL), listOf(skill))
        }
    }
}
