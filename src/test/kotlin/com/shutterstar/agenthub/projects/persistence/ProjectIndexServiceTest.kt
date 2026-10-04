package com.shutterstar.agenthub.projects.persistence

import com.shutterstar.agenthub.projects.discovery.ProjectDiscoveryResult
import com.shutterstar.agenthub.projects.model.AgentProject
import com.shutterstar.agenthub.projects.model.AgentSession
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProjectIndexServiceTest {
    @Test
    fun `cached projects survive state reload`() {
        val project = project("cached")
        val persisted = ProjectIndexStateMapper.encode(listOf(project), Instant.parse("2026-08-31T08:00:00Z"))
        val service = ProjectIndexService()

        service.loadState(persisted)

        assertEquals(listOf(project), service.cachedProjects())
        assertEquals(Instant.parse("2026-08-31T08:00:00Z"), service.lastRefreshedAt())
    }

    @Test
    fun `refresh runs asynchronously and updates cache when complete`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val project = project("fresh")
        val service = ProjectIndexService(
            discover = {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                ProjectDiscoveryResult(listOf(project), emptyList())
            },
            executor = executor,
            now = { Instant.parse("2026-08-31T09:00:00Z") },
        )

        try {
            val refresh = service.refreshInBackground()

            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(refresh.isDone)
            assertTrue(service.cachedProjects().isEmpty())
            assertSame(refresh, service.refreshInBackground())

            release.countDown()
            refresh.get(5, TimeUnit.SECONDS)

            assertEquals(listOf(project), service.cachedProjects())
            assertEquals(Instant.parse("2026-08-31T09:00:00Z"), service.lastRefreshedAt())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancelling the active refresh detaches it without blocking the caller`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val service = ProjectIndexService(
            discover = {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                ProjectDiscoveryResult(listOf(project("late")), emptyList())
            },
            executor = executor,
        )

        try {
            val refresh = service.refreshInBackground()
            assertTrue(started.await(5, TimeUnit.SECONDS))

            service.cancelActiveRefresh()

            assertTrue(refresh.isCancelled)
            assertTrue(service.cachedProjects().isEmpty())
            assertNotSame(refresh, service.refreshInBackground())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed refresh preserves existing cache`() {
        val existing = project("existing")
        val service = ProjectIndexService(
            discover = { error("failed provider orchestration") },
            executor = { command -> command.run() },
        )
        service.loadState(ProjectIndexStateMapper.encode(listOf(existing), Instant.EPOCH))

        runCatching { service.refreshInBackground().join() }

        assertEquals(listOf(existing), service.cachedProjects())
    }

    @Test
    fun `successful refresh notifies listeners after cache update`() {
        val fresh = project("fresh")
        val service = ProjectIndexService(
            discover = { ProjectDiscoveryResult(listOf(fresh), emptyList()) },
            executor = { command -> command.run() },
        )
        var observedProjects: List<DiscoveredProject> = emptyList()
        val subscription = service.addRefreshListener {
            observedProjects = service.cachedProjects()
        }

        try {
            service.refreshInBackground().join()
        } finally {
            subscription.close()
        }

        assertEquals(listOf(fresh), observedProjects)
    }

    @Test
    fun `session names are available after state reload`() {
        val session = AgentSession("s1", "claude", "K:/Projects/titled", null, Instant.EPOCH, null, title = "Fix login bug")
        val titled = project("titled").copy(
            agents = listOf(AgentProject("claude", "titled", 1, Instant.EPOCH, listOf(session))),
            lastActivity = Instant.EPOCH,
        )
        val service = ProjectIndexService(
            discover = { ProjectDiscoveryResult(listOf(titled), emptyList()) },
            executor = { command -> command.run() },
        )
        assertFalse(service.hasLiveResults())

        service.refreshInBackground().join()

        assertTrue(service.hasLiveResults())
        assertEquals("Fix login bug", service.cachedProjects().single().agents.single().sessions.single().title)
        val restarted = ProjectIndexService().apply { loadState(service.state) }
        assertFalse(restarted.hasLiveResults())
        assertEquals("Fix login bug", restarted.cachedProjects().single().agents.single().sessions.single().title)
    }

    @Test
    fun `cached projects only show visible agents and follow later visibility changes`() {
        val visible = mutableSetOf("claude")
        val stored = listOf(
            projectWith("mixed", "claude" to "2026-01-01T00:00:00Z", "codex" to "2026-09-01T00:00:00Z"),
            projectWith("codex-only", "codex" to "2026-08-01T00:00:00Z"),
        )
        val service = ProjectIndexService(
            discover = { ProjectDiscoveryResult(stored, emptyList()) },
            executor = java.util.concurrent.Executor { it.run() },
            isAgentVisible = { it in visible },
            detectionGeneration = { 0L },
            canDiscover = { true },
        )
        service.refreshInBackground().get(5, TimeUnit.SECONDS)

        val initial = service.cachedProjects()

        assertEquals(listOf("mixed"), initial.map { it.identity.id })
        assertEquals(listOf("claude"), initial.single().agents.map { it.agentId })
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), initial.single().lastActivity)

        visible += "codex"

        assertEquals(setOf("mixed", "codex-only"), service.cachedProjects().mapTo(mutableSetOf()) { it.identity.id })
        assertEquals(2, service.cachedProjects().first { it.identity.id == "mixed" }.agents.size)

        visible.clear()

        assertTrue(service.cachedProjects().isEmpty(), "nothing is shown while no agent is known to be installed")
    }

    @Test
    fun `a discovery that straddles a detection change is thrown away and redone`() {
        val generation = java.util.concurrent.atomic.AtomicLong()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val service = ProjectIndexService(
            discover = {
                val call = calls.incrementAndGet()
                if (call == 1) generation.incrementAndGet() // detection completes while the first run is in flight
                ProjectDiscoveryResult(listOf(projectWith("run-$call", "claude" to "2026-09-01T00:00:00Z")), emptyList())
            },
            executor = java.util.concurrent.Executor { it.run() },
            isAgentVisible = { true },
            detectionGeneration = { generation.get() },
            canDiscover = { true },
        )

        service.refreshInBackground().get(5, TimeUnit.SECONDS)

        assertEquals(2, calls.get())
        assertEquals(listOf("run-2"), service.cachedProjects().map { it.identity.id })
    }

    @Test
    fun `a flapping detection cannot keep discovery looping forever`() {
        val generation = java.util.concurrent.atomic.AtomicLong()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val service = ProjectIndexService(
            discover = {
                calls.incrementAndGet()
                generation.incrementAndGet()
                ProjectDiscoveryResult(emptyList(), emptyList())
            },
            executor = java.util.concurrent.Executor { it.run() },
            isAgentVisible = { true },
            detectionGeneration = { generation.get() },
            canDiscover = { true },
        )

        service.refreshInBackground().get(5, TimeUnit.SECONDS)

        assertEquals(3, calls.get())
    }

    @Test
    fun `refresh is skipped and the stored index kept while installation is unknown`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val stored = projectWith("kept", "claude" to "2026-09-01T00:00:00Z")
        val service = ProjectIndexService(
            discover = {
                calls.incrementAndGet()
                ProjectDiscoveryResult(emptyList(), emptyList())
            },
            executor = java.util.concurrent.Executor { it.run() },
            isAgentVisible = { true },
            detectionGeneration = { 0L },
            canDiscover = { false },
        )
        service.loadState(ProjectIndexStateMapper.encode(listOf(stored), Instant.parse("2026-09-02T00:00:00Z")))

        val result = service.refreshInBackground().get(5, TimeUnit.SECONDS)

        assertEquals(0, calls.get())
        assertTrue(result.projects.isEmpty())
        assertEquals(listOf("kept"), service.cachedProjects().map { it.identity.id })
    }

    @Test
    fun `the first filtered discovery replaces the stored index so removed agents do not linger`() {
        val stored = projectWith("mixed", "claude" to "2026-01-01T00:00:00Z", "codex" to "2026-09-01T00:00:00Z")
        val service = ProjectIndexService(
            discover = {
                ProjectDiscoveryResult(listOf(projectWith("mixed", "claude" to "2026-01-01T00:00:00Z")), emptyList())
            },
            executor = java.util.concurrent.Executor { it.run() },
            isAgentVisible = { true },
            detectionGeneration = { 0L },
            canDiscover = { true },
        )
        service.loadState(ProjectIndexStateMapper.encode(listOf(stored), Instant.parse("2026-09-02T00:00:00Z")))

        service.refreshInBackground().get(5, TimeUnit.SECONDS)

        val persisted = ProjectIndexStateMapper.decode(service.state)
        assertEquals(listOf("claude"), persisted.single().agents.map { it.agentId })
    }

    @Test
    fun `failed provider keeps its previous sessions while successful providers refresh`() {
        val previous = projectWith("mixed", "claude" to "2026-01-01T00:00:00Z", "codex" to "2026-01-02T00:00:00Z").let { project ->
            project.copy(agents = project.agents.map { agent ->
                agent.copy(sessions = listOf(AgentSession(agent.agentId, agent.agentId, project.path, null, agent.lastActivity, null)))
            })
        }
        val fresh = projectWith("mixed", "codex" to "2026-09-01T00:00:00Z")
        val service = ProjectIndexService(
            discover = {
                ProjectDiscoveryResult(
                    listOf(fresh),
                    listOf(com.shutterstar.agenthub.projects.discovery.ProjectDiscoveryWarning("claude", "Discovery timed out")),
                )
            },
            executor = java.util.concurrent.Executor { it.run() },
            isAgentVisible = { true },
        )
        service.loadState(ProjectIndexStateMapper.encode(listOf(previous), Instant.EPOCH))

        service.refreshInBackground().join()

        val agents = service.cachedProjects().single().agents.associateBy { it.agentId }
        assertEquals(setOf("claude", "codex"), agents.keys)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), agents.getValue("claude").lastActivity)
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), agents.getValue("codex").lastActivity)
        assertEquals(setOf("claude", "codex"), ProjectIndexStateMapper.decode(service.state).single().agents.mapTo(mutableSetOf()) { it.agentId })
    }
    private fun projectWith(id: String, vararg agents: Pair<String, String>) = project(id).copy(
        agents = agents.map { (agentId, activity) ->
            AgentProject(agentId, id, 1, Instant.parse(activity), emptyList())
        },
        lastActivity = agents.maxOf { Instant.parse(it.second) },
    )

    private fun project(id: String) = DiscoveredProject(
        identity = ProjectIdentity(id, "K:/Projects/$id", null, null),
        name = id,
        path = "K:/Projects/$id",
        gitRoot = null,
        gitRemote = null,
        currentBranch = null,
        agents = emptyList(),
        lastActivity = null,
    )
}
