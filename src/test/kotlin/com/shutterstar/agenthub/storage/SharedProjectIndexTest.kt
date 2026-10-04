package com.shutterstar.agenthub.storage

import com.shutterstar.agenthub.projects.discovery.ProjectDiscoveryResult
import com.shutterstar.agenthub.projects.model.AgentProject
import com.shutterstar.agenthub.projects.model.AgentSession
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import com.shutterstar.agenthub.projects.persistence.ProjectIndexService
import com.shutterstar.agenthub.projects.persistence.ProjectIndexState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executor

class SharedProjectIndexTest {
    @TempDir lateinit var directory: Path

    private fun store(home: AgentHubHome = AgentHubHome(directory), runtime: () -> String = { "host" }) =
        RuntimeStateStore(home, "projects", ProjectIndexState::class.java, ::ProjectIndexState, runtime, statIntervalMillis = 0)

    private fun project(id: String) = DiscoveredProject(
        ProjectIdentity(id, "/projects/$id", null, null), id, "/projects/$id", null, null, null,
        listOf(AgentProject("claude", id, 1, Instant.EPOCH, listOf(
            AgentSession(id, "claude", "/projects/$id", Instant.EPOCH, null, null,
                title = "PRIVATE TITLE", firstMessage = "PRIVATE PROMPT", messageCount = 3),
        ))), Instant.EPOCH,
    )

    private fun service(store: StateStore<ProjectIndexState>, id: String) = ProjectIndexService(
        discover = { ProjectDiscoveryResult(listOf(project(id)), emptyList()) },
        executor = Executor { it.run() }, isAgentVisible = { true }, store = store,
    )

    @Test fun `should share index across instances invalidate live results and keep session names`() {
        val first = service(store(), "a")
        first.refreshInBackground().join()
        assertEquals("PRIVATE PROMPT", first.cachedProjects().single().agents.single().sessions.single().firstMessage)
        val xml = Files.readString(directory.resolve("cache/host/projects.xml"))
        assertTrue(xml.contains("PRIVATE PROMPT"))
        assertTrue(xml.contains("PRIVATE TITLE"))
        val restarted = service(store(), "unused")
        val session = restarted.cachedProjects().single().agents.single().sessions.single()
        assertEquals("PRIVATE PROMPT", session.firstMessage)
        assertEquals("PRIVATE TITLE", session.title)
        assertEquals(3, session.messageCount)
        service(store(), "b").refreshInBackground().join()
        assertEquals("b", first.cachedProjects().single().identity.id)
        assertFalse(first.hasLiveResults())
    }

    @Test fun `should isolate runtimes and keep bound writes in their original partition`() {
        var runtime = "host"
        val changing = store(runtime = { runtime })
        val bound = changing.bound()
        service(changing, "host-project").refreshInBackground().join()
        runtime = "wsl-Ubuntu"
        assertTrue(changing.snapshot().projects.isEmpty())
        service(changing, "wsl-project").refreshInBackground().join()
        bound.update { it.copy(refreshedAtEpochMillis = 42) }
        assertEquals("wsl-project", changing.snapshot().projects.single().identityId)
        runtime = "host"
        assertEquals("host-project", changing.snapshot().projects.single().identityId)
        assertEquals(42, changing.snapshot().refreshedAtEpochMillis)
    }

    @Test fun `should reject a discovery that switches runtimes before completing`() {
        var runtime = "host"
        val changing = store(runtime = { runtime })
        val service = ProjectIndexService(
            discover = {
                runtime = "wsl-Ubuntu"
                ProjectDiscoveryResult(listOf(project("wrong-runtime")), emptyList())
            }, executor = Executor { it.run() }, store = changing,
        )
        assertThrows(Exception::class.java) { service.refreshInBackground().join() }
        assertTrue(changing.snapshot().projects.isEmpty())
        runtime = "host"
        assertTrue(changing.snapshot().projects.isEmpty())
    }
}
