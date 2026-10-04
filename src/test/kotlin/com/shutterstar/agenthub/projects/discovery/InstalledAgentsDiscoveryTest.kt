package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.InstalledAgentPolicy
import com.shutterstar.agenthub.projects.model.RawAgentProject
import com.shutterstar.agenthub.projects.resolve.ProjectResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Installed-agents-only behaviour of the project discovery: hidden providers never run. */
class InstalledAgentsDiscoveryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `providers of agents that are not installed or unknown are neither probed nor scanned nor warned about`() {
        val calls = mutableListOf<String>()
        val results = mapOf("claude" to true, "codex" to false) // cursor is missing = unknown
        val service = ProjectDiscoveryService(
            providers = listOf("claude", "codex", "cursor").map { tracking(it, calls) },
            projectResolver = ProjectResolver { null },
            isAgentRelevant = { InstalledAgentPolicy.isVisible(it, results) },
        )

        val result = service.discover()

        assertEquals(listOf("available:claude", "discover:claude"), calls)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `while nothing is known no provider runs at all`() {
        val calls = mutableListOf<String>()
        val service = ProjectDiscoveryService(
            providers = listOf(tracking("claude", calls), tracking("codex", calls)),
            projectResolver = ProjectResolver { null },
            isAgentRelevant = { InstalledAgentPolicy.isVisible(it, null) },
        )

        val result = service.discover()

        assertTrue(calls.isEmpty())
        assertTrue(result.projects.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `reinstalling an agent brings back the sessions that are still on disk and only those`() {
        val project = Files.createDirectories(temporaryDirectory.resolve("work"))
        val sessions = Files.createDirectories(temporaryDirectory.resolve("sessions"))
        Files.writeString(sessions.resolve("kept.jsonl"), "{}")
        Files.writeString(sessions.resolve("deleted.jsonl"), "{}")
        var installed = false
        val provider = object : AgentProjectProvider {
            override val agentId = "claude"
            override fun isAvailable() = true
            override fun discover(): List<RawAgentProject> = Files.list(sessions).use { files ->
                files.map { file ->
                    RawAgentProject(
                        agentId = "claude",
                        rawProjectPath = project.toString(),
                        sessionId = file.fileName.toString().removeSuffix(".jsonl"),
                        startedAt = Instant.parse("2026-09-01T10:00:00Z"),
                        updatedAt = Instant.parse("2026-09-01T11:00:00Z"),
                        sourcePath = file.toString(),
                    )
                }.toList()
            }
        }
        val service = ProjectDiscoveryService(
            providers = listOf(provider),
            projectResolver = ProjectResolver { null },
            isAgentRelevant = { installed },
        )

        assertTrue(service.discover().projects.isEmpty(), "uninstalled: hidden")

        installed = true
        Files.delete(sessions.resolve("deleted.jsonl")) // cleaned up while the agent was gone
        val reinstalled = service.discover().projects.single().agents.single().sessions

        assertEquals(listOf("kept"), reinstalled.map { it.id })
    }

    private fun tracking(agentId: String, calls: MutableList<String>) = object : AgentProjectProvider {
        override val agentId = agentId
        override fun isAvailable(): Boolean {
            calls += "available:$agentId"
            return true
        }

        override fun discover(): List<RawAgentProject> {
            calls += "discover:$agentId"
            return emptyList()
        }
    }
}
