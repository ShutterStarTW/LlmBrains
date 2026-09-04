package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.model.RawAgentProject
import com.shutterstar.agenthub.projects.resolve.GitProjectInfo
import com.shutterstar.agenthub.projects.resolve.ProjectResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class ProjectDiscoveryServiceTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `groups sessions by project and agent`() {
        val projectA = Files.createDirectories(tempDirectory.resolve("A"))
        val projectB = Files.createDirectories(tempDirectory.resolve("B"))
        val service = service(
            provider(
                "claude",
                raw("claude", projectA, "c1", "2026-08-01T10:00:00Z"),
                raw("claude", projectA, "c2", "2026-08-02T10:00:00Z"),
            ),
            provider("codex", raw("codex", projectA, "x1", "2026-08-03T10:00:00Z")),
            provider("cursor", raw("cursor", projectB, "u1", "2026-08-04T10:00:00Z")),
        )

        val projects = service.discoverProjects()

        assertEquals(2, projects.size)
        val discoveredA = projects.single { it.name == "A" }
        assertEquals(listOf("claude", "codex"), discoveredA.agents.map { it.agentId })
        assertEquals(2, discoveredA.agents.single { it.agentId == "claude" }.sessionCount)
        assertEquals(1, discoveredA.agents.single { it.agentId == "codex" }.sessionCount)
        assertEquals(Instant.parse("2026-08-03T10:00:00Z"), discoveredA.lastActivity)
    }

    @Test
    fun `normalized Git remote merges projects reported at different paths`() {
        val claudePath = Files.createDirectories(tempDirectory.resolve("claude-copy"))
        val codexPath = Files.createDirectories(tempDirectory.resolve("codex-copy"))
        val resolver = ProjectResolver { path ->
            val remote = if (path.endsWith("claude-copy")) {
                "git@github.com:team/repository.git"
            } else {
                "https://github.com/team/repository"
            }
            GitProjectInfo(path, remote, "main")
        }
        val service = ProjectDiscoveryService(
            providers = listOf(
                provider("claude", raw("claude", claudePath, "c1", "2026-08-01T10:00:00Z")),
                provider("codex", raw("codex", codexPath, "x1", "2026-08-02T10:00:00Z")),
            ),
            projectResolver = resolver,
        )

        val projects = service.discoverProjects()

        assertEquals(1, projects.size)
        assertEquals("github.com/team/repository", projects.single().gitRemote)
        assertEquals(listOf("claude", "codex"), projects.single().agents.map { it.agentId })
    }

    @Test
    fun `duplicate session records count once and preserve the newest metadata`() {
        val project = Files.createDirectories(tempDirectory.resolve("duplicates"))
        val provider = provider(
            "claude",
            raw("claude", project, "same", "2026-08-01T10:00:00Z"),
            raw("claude", project, "same", "2026-08-03T10:00:00Z", title = "Latest title"),
        )

        val discovered = service(provider).discoverProjects().single().agents.single()

        assertEquals(1, discovered.sessionCount)
        assertEquals("Latest title", discovered.sessions.single().title)
        assertEquals(Instant.parse("2026-08-03T10:00:00Z"), discovered.lastActivity)
    }

    @Test
    fun `one failing provider produces a warning without losing other results`() {
        val project = Files.createDirectories(tempDirectory.resolve("healthy"))
        val failing = object : AgentProjectProvider {
            override val agentId = "cursor"
            override fun isAvailable() = true
            override fun discover(): List<RawAgentProject> = error("broken fixture")
        }
        val service = service(
            failing,
            provider("codex", raw("codex", project, "ok", "2026-08-01T10:00:00Z")),
        )

        val result = service.discover()

        assertEquals(1, result.projects.size)
        assertEquals("cursor", result.warnings.single().agentId)
        assertTrue(result.warnings.single().message.startsWith("Discovery failed"))
        assertFalse(result.warnings.single().message.contains("broken fixture"))
    }

    @Test
    fun `unavailable and irrelevant providers are not scanned`() {
        val calls = mutableListOf<String>()
        val unavailable = trackingProvider("claude", available = false, calls = calls)
        val irrelevant = trackingProvider("cursor", available = true, calls = calls)
        val service = ProjectDiscoveryService(
            providers = listOf(unavailable, irrelevant),
            projectResolver = ProjectResolver { null },
            isAgentRelevant = { it == "claude" },
        )

        val result = service.discover()

        assertTrue(result.projects.isEmpty())
        assertTrue(result.warnings.isEmpty())
        assertEquals(listOf("available:claude"), calls)
    }

    @Test
    fun `nativeResumeId is populated for Claude, Codex and OpenCode but not other agents`() {
        val project = Files.createDirectories(tempDirectory.resolve("resume"))
        val service = service(
            provider("claude", raw("claude", project, "claude-session", "2026-08-01T10:00:00Z")),
            provider("codex", raw("codex", project, "codex-session", "2026-08-01T10:00:00Z")),
            provider("opencode", raw("opencode", project, "opencode-session", "2026-08-01T10:00:00Z")),
            provider("cline", raw("cline", project, "cline-session", "2026-08-01T10:00:00Z")),
        )

        val agents = service.discoverProjects().single().agents.associateBy { it.agentId }

        assertEquals("claude-session", agents.getValue("claude").sessions.single().nativeResumeId)
        assertEquals("codex-session", agents.getValue("codex").sessions.single().nativeResumeId)
        assertEquals("opencode-session", agents.getValue("opencode").sessions.single().nativeResumeId)
        assertEquals(null, agents.getValue("cline").sessions.single().nativeResumeId)
    }

    @Test
    fun `records claiming another provider agent id are ignored`() {
        val project = Files.createDirectories(tempDirectory.resolve("mismatch"))
        val service = service(
            provider("claude", raw("codex", project, "wrong", "2026-08-01T10:00:00Z")),
        )

        assertTrue(service.discoverProjects().isEmpty())
    }

    @Test
    fun `stuck providers share a single discovery deadline instead of accumulating timeouts`() {
        val stuckProviders = (1..6).map { index ->
            object : AgentProjectProvider {
                override val agentId = "stuck-$index"
                override fun isAvailable() = true
                override fun discover(): List<RawAgentProject> {
                    Thread.sleep(5_000)
                    return emptyList()
                }
            }
        }
        val service = ProjectDiscoveryService(
            providers = stuckProviders,
            projectResolver = ProjectResolver { null },
            providerTimeoutMillis = 200,
        )

        val result = assertTimeout(
            Duration.ofMillis(900),
            ThrowingSupplier { service.discover() },
        )

        assertEquals(6, result.warnings.size)
        assertTrue(result.warnings.all { it.message == "Discovery timed out" })
    }

    @Test
    fun `ten thousand sessions resolve without repeated Git lookups`() {
        val project = Files.createDirectories(tempDirectory.resolve("large-history"))
        val sessions = List(10_000) { index ->
            val activity = Instant.EPOCH.plusSeconds(index.toLong())
            RawAgentProject(
                agentId = "codex",
                rawProjectPath = project.toString(),
                sessionId = "session-$index",
                startedAt = activity.minusSeconds(60),
                updatedAt = activity,
                sourcePath = "fixture/session-$index.jsonl",
            )
        }
        val gitLookups = AtomicInteger()
        val service = ProjectDiscoveryService(
            providers = listOf(provider("codex", *sessions.toTypedArray())),
            projectResolver = ProjectResolver { path ->
                gitLookups.incrementAndGet()
                GitProjectInfo(path, "https://github.com/team/large-history.git", "main")
            },
        )

        val projects = assertTimeout(
            Duration.ofSeconds(5),
            ThrowingSupplier { service.discoverProjects() },
        )

        assertEquals(1, projects.size)
        assertEquals(10_000, projects.single().agents.single().sessionCount)
        assertEquals(1, gitLookups.get())
    }

    private fun service(vararg providers: AgentProjectProvider) = ProjectDiscoveryService(
        providers = providers.toList(),
        projectResolver = ProjectResolver { null },
    )

    private fun provider(agentId: String, vararg sessions: RawAgentProject) = object : AgentProjectProvider {
        override val agentId = agentId
        override fun isAvailable() = true
        override fun discover() = sessions.toList()
    }

    private fun trackingProvider(
        agentId: String,
        available: Boolean,
        calls: MutableList<String>,
    ) = object : AgentProjectProvider {
        override val agentId = agentId
        override fun isAvailable(): Boolean {
            calls += "available:$agentId"
            return available
        }

        override fun discover(): List<RawAgentProject> {
            calls += "discover:$agentId"
            return emptyList()
        }
    }

    private fun raw(
        agentId: String,
        path: Path,
        sessionId: String,
        updatedAt: String,
        title: String? = null,
    ) = RawAgentProject(
        agentId = agentId,
        rawProjectPath = path.toString(),
        sessionId = sessionId,
        startedAt = Instant.parse(updatedAt).minusSeconds(60),
        updatedAt = Instant.parse(updatedAt),
        sourcePath = "fixture/$sessionId.jsonl",
        metadata = title?.let { mapOf("title" to it) }.orEmpty(),
    )
}
