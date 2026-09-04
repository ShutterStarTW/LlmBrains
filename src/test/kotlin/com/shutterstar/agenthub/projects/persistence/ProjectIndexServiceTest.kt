package com.shutterstar.agenthub.projects.persistence

import com.shutterstar.agenthub.projects.discovery.ProjectDiscoveryResult
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
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
