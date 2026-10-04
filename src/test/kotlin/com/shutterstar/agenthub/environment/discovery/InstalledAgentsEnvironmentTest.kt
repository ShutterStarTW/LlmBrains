package com.shutterstar.agenthub.environment.discovery

import com.shutterstar.agenthub.writeSkill
import com.shutterstar.agenthub.project
import com.shutterstar.agenthub.environment.instructions.discovery.ClaudeInstructionProvider
import com.shutterstar.agenthub.environment.instructions.discovery.CodexInstructionProvider
import com.shutterstar.agenthub.environment.instructions.discovery.InstructionDiscoveryService
import com.shutterstar.agenthub.environment.instructions.discovery.InstructionProvider
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.mcp.discovery.ClaudeMcpProvider
import com.shutterstar.agenthub.environment.mcp.discovery.CodexMcpProvider
import com.shutterstar.agenthub.environment.mcp.discovery.McpDiscoveryService
import com.shutterstar.agenthub.environment.mcp.discovery.McpProvider
import com.shutterstar.agenthub.environment.mcp.discovery.RawMcpServer
import com.shutterstar.agenthub.environment.skills.discovery.ClaudeSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.CodexSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SharedSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SkillDiscoveryService
import com.shutterstar.agenthub.environment.skills.discovery.SkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SkillSourceRecord
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Installed-agents-only behaviour of the Skills / MCP / Instructions discovery. */
class InstalledAgentsEnvironmentTest {
    @TempDir
    lateinit var home: Path

    @Test
    fun `hidden skill, MCP and instruction providers are not run for global or project scans`() {
        val calls = mutableListOf<String>()
        val project = project(Files.createDirectories(home.resolve("p")), "claude", "codex")
        val visible: (String) -> Boolean = { it == "claude" }
        val skills = SkillDiscoveryService(
            listOf(skillProvider("claude", calls), skillProvider("codex", calls)),
            isAgentVisible = visible,
        )
        val mcp = McpDiscoveryService(
            listOf(mcpProvider("claude", calls), mcpProvider("codex", calls)),
            isAgentVisible = visible,
        )
        val instructions = InstructionDiscoveryService(
            listOf(instructionProvider("claude", calls), instructionProvider("codex", calls)),
            isAgentVisible = visible,
        )

        skills.discoverGlobal()
        skills.discoverProject(project)
        mcp.discoverGlobal()
        mcp.discoverProject(project)
        instructions.discoverGlobal()
        instructions.discoverProject(project)

        assertEquals(6, calls.size)
        assertTrue(calls.all { it.startsWith("claude:") }, "only the installed agent is scanned: $calls")
    }

    @Test
    fun `the shared skills root is scanned only while a compatible agent is installed`() {
        writeSkill(home.resolve(".agents/skills/shared-skill"), "shared-skill")
        fun discover(vararg installed: String) =
            SkillDiscoveryService(listOf(SharedSkillProvider(home)), isAgentVisible = { it in installed }).discoverGlobal()

        assertTrue(discover().isEmpty(), "nothing installed")
        assertTrue(discover("claude").isEmpty(), "claude does not read .agents/skills")

        val skill = discover("claude", "codex").single()
        assertEquals(setOf("codex"), skill.compatibleAgents, "compatibility is limited to installed agents")
    }

    @Test
    fun `a project environment ignores hidden agents skills, MCP servers and instructions`() {
        val root = Files.createDirectories(home.resolve("project"))
        writeSkill(root.resolve(".claude/skills/claude-skill"), "claude-skill")
        writeSkill(root.resolve(".codex/skills/codex-skill"), "codex-skill")
        Files.writeString(root.resolve("CLAUDE.md"), "Claude instructions")
        Files.writeString(root.resolve("AGENTS.md"), "Codex instructions")
        Files.writeString(root.resolve(".mcp.json"), """{"mcpServers":{"only-claude":{"command":"npx"}}}""")
        Files.createDirectories(root.resolve(".codex"))
        Files.writeString(root.resolve(".codex/config.toml"), "[mcp_servers.only-codex]\nurl = \"https://x.test/mcp\"\n")
        val visible: (String) -> Boolean = { it == "claude" }
        val service = ProjectEnvironmentDiscoveryService(
            configDiscovery = com.shutterstar.agenthub.environment.config.discovery.ConfigDiscoveryService(emptyList()),
            skillDiscovery = SkillDiscoveryService(
                listOf(ClaudeSkillProvider(home), CodexSkillProvider(home)),
                isAgentVisible = visible,
            ),
            mcpDiscovery = McpDiscoveryService(
                listOf(ClaudeMcpProvider(home), CodexMcpProvider(home.resolve(".codex"))),
                isAgentVisible = visible,
            ),
            instructionDiscovery = InstructionDiscoveryService(
                listOf(ClaudeInstructionProvider(home), CodexInstructionProvider(home.resolve(".codex"))),
                isAgentVisible = visible,
            ),
            isAgentVisible = visible,
        )

        val environment = service.discover(project(root, "claude", "codex"))

        assertEquals(setOf("claude"), environment.agentIds)
        assertEquals(listOf("claude-skill"), environment.skills.map { it.name })
        assertEquals(listOf("only-claude"), environment.mcpServers.map { it.name })
        assertTrue(environment.instructions.isNotEmpty())
        assertTrue(environment.instructions.all { source -> source.agentIds == setOf("claude") })
    }

    @Test
    fun `the cache is rebuilt after invalidation when an agent becomes visible again`() {
        val root = Files.createDirectories(home.resolve("reinstall"))
        writeSkill(root.resolve(".codex/skills/codex-skill"), "codex-skill")
        val installed = mutableSetOf("claude")
        val visible: (String) -> Boolean = { it in installed }
        val service = ProjectEnvironmentDiscoveryService(
            configDiscovery = com.shutterstar.agenthub.environment.config.discovery.ConfigDiscoveryService(emptyList()),
            skillDiscovery = SkillDiscoveryService(
                listOf(ClaudeSkillProvider(home), CodexSkillProvider(home)),
                isAgentVisible = visible,
            ),
            mcpDiscovery = McpDiscoveryService(emptyList()),
            instructionDiscovery = InstructionDiscoveryService(emptyList()),
            isAgentVisible = visible,
        )
        val project = project(root, "claude", "codex")

        assertTrue(service.discover(project).skills.isEmpty())

        installed += "codex"
        service.invalidateAll()

        assertEquals(listOf("codex-skill"), service.discover(project).skills.map { it.name })
    }

    @Test
    fun `a direct request for a hidden agents environment returns nothing`() {
        val root = Files.createDirectories(home.resolve("direct"))
        writeSkill(root.resolve(".codex/skills/codex-skill"), "codex-skill")
        val project = project(root, "codex")
        val environment = ProjectEnvironmentDiscoveryService(
            configDiscovery = com.shutterstar.agenthub.environment.config.discovery.ConfigDiscoveryService(emptyList()),
            skillDiscovery = SkillDiscoveryService(listOf(CodexSkillProvider(home))),
            mcpDiscovery = McpDiscoveryService(emptyList()),
            instructionDiscovery = InstructionDiscoveryService(emptyList()),
        )

        val hidden = AgentEnvironmentDiscoveryService(environment) { false }.discover("codex", listOf(project))
        val shown = AgentEnvironmentDiscoveryService(environment) { true }.discover("codex", listOf(project))

        assertTrue(hidden.skills.isEmpty() && hidden.mcpServers.isEmpty() && hidden.instructions.isEmpty())
        assertEquals(listOf("codex-skill"), shown.skills.map { it.name })
    }

    private fun skillProvider(id: String, calls: MutableList<String>) = object : SkillProvider {
        override val agentId: String? = id

        override fun discoverGlobal(): List<SkillSourceRecord> {
            calls += "$id:skill-global"
            return emptyList()
        }

        override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
            calls += "$id:skill-project"
            return emptyList()
        }
    }

    private fun mcpProvider(id: String, calls: MutableList<String>) = object : McpProvider {
        override val agentId = id

        override fun discoverGlobal(): List<RawMcpServer> {
            calls += "$id:mcp-global"
            return emptyList()
        }

        override fun discoverProject(project: DiscoveredProject): List<RawMcpServer> {
            calls += "$id:mcp-project"
            return emptyList()
        }
    }

    private fun instructionProvider(id: String, calls: MutableList<String>) = object : InstructionProvider {
        override val agentId = id

        override fun discoverGlobal(): List<InstructionSource> {
            calls += "$id:instruction-global"
            return emptyList()
        }

        override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
            calls += "$id:instruction-project"
            return emptyList()
        }
    }

}