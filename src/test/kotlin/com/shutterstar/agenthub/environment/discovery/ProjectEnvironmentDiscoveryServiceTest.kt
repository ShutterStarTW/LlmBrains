package com.shutterstar.agenthub.environment.discovery

import com.shutterstar.agenthub.environment.instructions.discovery.ClaudeInstructionProvider
import com.shutterstar.agenthub.environment.instructions.discovery.CodexInstructionProvider
import com.shutterstar.agenthub.environment.instructions.discovery.CursorInstructionProvider
import com.shutterstar.agenthub.environment.instructions.discovery.InstructionDiscoveryService
import com.shutterstar.agenthub.environment.instructions.discovery.InstructionProvider
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.environment.mcp.discovery.ClaudeMcpProvider
import com.shutterstar.agenthub.environment.mcp.discovery.CodexMcpProvider
import com.shutterstar.agenthub.environment.mcp.discovery.McpDiscoveryService
import com.shutterstar.agenthub.environment.mcp.discovery.McpProvider
import com.shutterstar.agenthub.environment.mcp.discovery.RawMcpServer
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.environment.skills.discovery.ClaudeSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.CodexSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SharedSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SkillDiscoveryService
import com.shutterstar.agenthub.environment.skills.discovery.SkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SkillSourceRecord
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.AgentProject
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProjectEnvironmentDiscoveryServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should aggregate skills MCP servers and instructions across agents`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"))
        writeSkill(projectRoot.resolve(".claude/skills/php-review"), "php-review")
        writeSkill(projectRoot.resolve(".codex/skills/php-review"), "php-review")
        writeSkill(projectRoot.resolve(".agents/skills/docker-debug"), "docker-debug")
        Files.writeString(projectRoot.resolve("AGENTS.md"), "Shared instructions")
        Files.writeString(projectRoot.resolve("CLAUDE.md"), "Claude instructions")
        Files.writeString(
            projectRoot.resolve(".mcp.json"),
            """{"mcpServers":{"playwright":{"command":"npx","args":["@playwright/mcp"]}}}""",
        )
        val projectCodex = Files.createDirectories(projectRoot.resolve(".codex"))
        Files.writeString(
            projectCodex.resolve("config.toml"),
            """
            [mcp_servers.github]
            url = "https://github.test/mcp"
            """.trimIndent(),
        )

        val service = ProjectEnvironmentDiscoveryService(
            skillDiscovery = SkillDiscoveryService(
                listOf(
                    SharedSkillProvider(temporaryDirectory),
                    ClaudeSkillProvider(temporaryDirectory),
                    CodexSkillProvider(temporaryDirectory),
                ),
            ),
            mcpDiscovery = McpDiscoveryService(
                listOf(
                    ClaudeMcpProvider(temporaryDirectory),
                    CodexMcpProvider(temporaryDirectory.resolve(".codex")),
                ),
            ),
            instructionDiscovery = InstructionDiscoveryService(
                listOf(
                    CodexInstructionProvider(temporaryDirectory.resolve(".codex")),
                    ClaudeInstructionProvider(temporaryDirectory),
                    CursorInstructionProvider(temporaryDirectory),
                ),
            ),
        )
        val environment = service.discover(project(projectRoot))

        assertEquals(setOf("php-review", "docker-debug"), environment.skills.mapTo(mutableSetOf()) { it.name })
        assertEquals(setOf("playwright", "github"), environment.mcpServers.mapTo(mutableSetOf()) { it.name })
        assertEquals(2, environment.instructions.size)
        assertEquals(setOf("claude", "codex"), environment.agentIds)

        val claude = service.forAgent(environment, "claude")
        val codex = service.forAgent(environment, "codex")
        assertEquals(listOf("playwright"), claude.mcpServers.map { it.name })
        assertEquals(listOf("github"), codex.mcpServers.map { it.name })
        assertTrue(claude.instructions.any { it.path.endsWith("CLAUDE.md") })
        assertTrue(codex.instructions.any { it.path.endsWith("AGENTS.md") })
    }

    @Test
    fun `should reuse cached discovery until manually invalidated`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("cached-project"))
        val service = ProjectEnvironmentDiscoveryService(
            skillDiscovery = SkillDiscoveryService(emptyList()),
            mcpDiscovery = McpDiscoveryService(emptyList()),
            instructionDiscovery = InstructionDiscoveryService(
                listOf(CodexInstructionProvider(temporaryDirectory.resolve(".codex"))),
            ),
        )
        val discoveredProject = project(projectRoot)

        assertTrue(service.discover(discoveredProject).instructions.isEmpty())
        Files.writeString(projectRoot.resolve("AGENTS.md"), "New instructions")
        assertTrue(service.discover(discoveredProject).instructions.isEmpty())

        service.invalidateAll()

        assertEquals(1, service.discover(discoveredProject).instructions.size)
    }

    @Test
    fun `should not restore a stale cache entry after invalidation`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("concurrent-project"))
        val firstDiscoveryStarted = CountDownLatch(1)
        val releaseFirstDiscovery = CountDownLatch(1)
        val invocations = AtomicInteger()
        val persisted = AtomicInteger()
        val blockingProvider = object : SkillProvider {
            override val agentId: String = "test"

            override fun discoverGlobal(): List<SkillSourceRecord> {
                if (invocations.incrementAndGet() == 1) {
                    firstDiscoveryStarted.countDown()
                    if (!releaseFirstDiscovery.await(5, TimeUnit.SECONDS)) {
                        error("Timed out waiting to release the first discovery")
                    }
                }
                return emptyList()
            }

            override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> = emptyList()
        }
        val service = ProjectEnvironmentDiscoveryService(
            skillDiscovery = SkillDiscoveryService(listOf(blockingProvider)),
            mcpDiscovery = McpDiscoveryService(emptyList()),
            instructionDiscovery = InstructionDiscoveryService(emptyList()),
            persist = { _, _ -> persisted.incrementAndGet() },
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val firstDiscovery = executor.submit { service.discover(project(projectRoot)) }
            assertTrue(firstDiscoveryStarted.await(5, TimeUnit.SECONDS))

            service.invalidateAll()
            releaseFirstDiscovery.countDown()
            firstDiscovery.get(5, TimeUnit.SECONDS)
            assertEquals(0, persisted.get())

            service.discover(project(projectRoot))
            assertEquals(2, invocations.get())
            assertEquals(1, persisted.get())
        } finally {
            releaseFirstDiscovery.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `global discovery runs once and is reused across multiple projects`() {
        val globalCalls = AtomicInteger()
        val projectCalls = AtomicInteger()
        val countingProvider = object : SkillProvider {
            override val agentId: String = "test"

            override fun discoverGlobal(): List<SkillSourceRecord> {
                globalCalls.incrementAndGet()
                return emptyList()
            }

            override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
                projectCalls.incrementAndGet()
                return emptyList()
            }
        }
        val service = ProjectEnvironmentDiscoveryService(
            skillDiscovery = SkillDiscoveryService(listOf(countingProvider)),
            mcpDiscovery = McpDiscoveryService(emptyList()),
            instructionDiscovery = InstructionDiscoveryService(emptyList()),
        )
        val projectA = project(Files.createDirectories(temporaryDirectory.resolve("multi-a")))
        val projectB = project(Files.createDirectories(temporaryDirectory.resolve("multi-b")))
        val projectC = project(Files.createDirectories(temporaryDirectory.resolve("multi-c")))

        service.discover(projectA)
        service.discover(projectB)
        service.discover(projectC)

        assertEquals(1, globalCalls.get())
        assertEquals(3, projectCalls.get())

        service.invalidateAll()
        service.discover(projectA)

        assertEquals(2, globalCalls.get())
    }

    @Test
    fun `a failing provider produces a structured warning without losing healthy results`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("warning-project"))
        val failingSkillProvider = object : SkillProvider {
            override val agentId: String = "claude"

            override fun discoverGlobal(): List<SkillSourceRecord> = error("fixture failure")

            override fun discoverProject(project: DiscoveredProject) = emptyList<SkillSourceRecord>()
        }
        val service = ProjectEnvironmentDiscoveryService(
            skillDiscovery = SkillDiscoveryService(listOf(failingSkillProvider)),
            mcpDiscovery = McpDiscoveryService(listOf(ClaudeMcpProvider(temporaryDirectory))),
            instructionDiscovery = InstructionDiscoveryService(emptyList()),
        )

        val environment = service.discover(project(projectRoot))

        assertTrue(environment.skills.isEmpty())
        val warning = environment.warnings.single()
        assertEquals("skill", warning.capability)
        assertEquals("claude", warning.agentId)
        assertTrue(warning.message.contains("IllegalStateException"))
    }

    @Test
    fun `project environment excludes agent-specific records for unrelated agents`() {
        val projectRoot = Files.createDirectories(temporaryDirectory.resolve("scoped-project"))
        val unrelatedSkillProvider = object : SkillProvider {
            override val agentId: String = "other"

            override fun discoverGlobal(): List<SkillSourceRecord> = listOf(
                SkillSourceRecord("other-skill", "/other/skill", null, "other", SkillScope.GLOBAL, false, "other"),
                SkillSourceRecord("shared-skill", "/shared/skill", null, null, SkillScope.GLOBAL, true, "shared"),
            )

            override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> = emptyList()
        }
        val unrelatedMcpProvider = object : McpProvider {
            override val agentId: String = "other"

            override fun discoverGlobal(): List<RawMcpServer> = listOf(
                RawMcpServer(
                    agentId = agentId,
                    name = "other-mcp",
                    transport = McpTransport.STDIO,
                    command = "other",
                    args = emptyList(),
                    url = null,
                    environmentVariableNames = emptySet(),
                    configPath = "/other/mcp.json",
                    scope = McpScope.GLOBAL,
                ),
            )

            override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> = emptyList()
        }
        val unrelatedInstructionProvider = object : InstructionProvider {
            override val agentId: String = "other"

            override fun discoverGlobal(): List<InstructionSource> = listOf(
                InstructionSource(
                    path = "/other/AGENTS.md",
                    scope = InstructionScope.GLOBAL,
                    agentIds = setOf(agentId),
                    type = InstructionType.AGENTS_MD,
                ),
            )

            override fun discoverProject(project: DiscoveredProject): List<InstructionSource> = emptyList()
        }
        val service = ProjectEnvironmentDiscoveryService(
            skillDiscovery = SkillDiscoveryService(listOf(unrelatedSkillProvider)),
            mcpDiscovery = McpDiscoveryService(listOf(unrelatedMcpProvider)),
            instructionDiscovery = InstructionDiscoveryService(listOf(unrelatedInstructionProvider)),
        )

        val environment = service.discover(project(projectRoot))

        assertEquals(listOf("shared-skill"), environment.skills.map { it.name })
        assertTrue(environment.mcpServers.isEmpty())
        assertTrue(environment.instructions.isEmpty())
    }

    private fun writeSkill(directory: Path, name: String) {
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve("SKILL.md"),
            "---\nname: $name\ndescription: Test skill\n---\nInstructions",
        )
    }

    private fun project(root: Path): DiscoveredProject = DiscoveredProject(
        identity = ProjectIdentity("project", root.toString(), root.toString(), null),
        name = "project",
        path = root.toString(),
        gitRoot = root.toString(),
        gitRemote = null,
        currentBranch = null,
        agents = listOf(
            AgentProject("claude", "project", 1, null, emptyList()),
            AgentProject("codex", "project", 1, null, emptyList()),
        ),
        lastActivity = null,
    )
}
