package com.shutterstar.agenthub.environment.discovery

import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.environment.model.AgentEnvironment
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentEnvironmentDiscoveryServiceTest {
    @Test
    fun `should deduplicate global skills and retain project-specific copies`() {
        val global = skill("global", SkillScope.GLOBAL, "/home/.agents/skills/review")
        val firstProject = skill("project", SkillScope.PROJECT, "/first/.agents/skills/review")
        val secondProject = skill("project", SkillScope.PROJECT, "/second/.agents/skills/review")
        val instruction = InstructionSource(
            path = "/home/.claude/CLAUDE.md",
            scope = InstructionScope.GLOBAL,
            agentIds = setOf("claude"),
            type = InstructionType.CLAUDE_MD,
        )

        val result = AgentEnvironmentDiscoveryService(ProjectEnvironmentDiscoveryService()).aggregate(
            "claude",
            listOf(
                AgentEnvironment("claude", listOf(global, firstProject), emptyList(), listOf(instruction)),
                AgentEnvironment("claude", listOf(global, secondProject), emptyList(), listOf(instruction)),
            ),
        )

        assertEquals(3, result.skills.size)
        assertEquals(1, result.skills.count { it.scope == SkillScope.GLOBAL })
        assertEquals(2, result.skills.count { it.scope == SkillScope.PROJECT })
        assertEquals(1, result.instructions.size)
    }

    @Test fun `should build cached agent view from related projects without discovery`() {
        val discovery = ProjectEnvironmentDiscoveryService(persist = { _, _ -> })
        val related = project("related", "claude")
        val unrelated = project("unrelated", "codex")
        val global = skill("global", SkillScope.GLOBAL, "/home/.claude/skills/review")
        val cached = mapOf(
            "related" to com.shutterstar.agenthub.environment.model.ProjectEnvironment("related", setOf("claude"), listOf(global), emptyList(), emptyList()),
            "unrelated" to com.shutterstar.agenthub.environment.model.ProjectEnvironment("unrelated", setOf("claude"), listOf(skill("unrelated", SkillScope.PROJECT, "/other/skill")), emptyList(), emptyList()),
        )
        val service = AgentEnvironmentDiscoveryService(discovery, cachedEnvironments = { cached })
        val result = service.cached("claude", listOf(related, unrelated))!!
        assertEquals(listOf("global"), result.skills.map { it.identity.id })
        org.junit.jupiter.api.Assertions.assertNull(discovery.cachedGlobalConfigsForAgent("claude"), "Cached lookup must not trigger global discovery")
        org.junit.jupiter.api.Assertions.assertNull(service.cached("claude", listOf(unrelated)))
        assertEquals(emptyList<AgentSkill>(), AgentEnvironmentDiscoveryService(discovery, cachedEnvironments = { error("Hidden agent must not read snapshots") }, isAgentVisible = { false })
            .cached("claude", listOf(related))!!.skills)
    }

    @Test fun `should reuse global snapshot without projects and drop it on invalidation`() {
        val discovery = ProjectEnvironmentDiscoveryService(
            skillDiscovery = com.shutterstar.agenthub.environment.skills.discovery.SkillDiscoveryService(emptyList()),
            mcpDiscovery = com.shutterstar.agenthub.environment.mcp.discovery.McpDiscoveryService(emptyList()),
            instructionDiscovery = com.shutterstar.agenthub.environment.instructions.discovery.InstructionDiscoveryService(emptyList()),
            configDiscovery = com.shutterstar.agenthub.environment.config.discovery.ConfigDiscoveryService(emptyList()),
            persist = { _, _ -> },
        )
        val service = AgentEnvironmentDiscoveryService(discovery, cachedEnvironments = { emptyMap() })
        org.junit.jupiter.api.Assertions.assertNull(service.cached("claude", emptyList()))
        discovery.globalConfigsForAgent("claude")
        org.junit.jupiter.api.Assertions.assertNotNull(service.cached("claude", emptyList()))
        discovery.invalidateAll()
        org.junit.jupiter.api.Assertions.assertNull(service.cached("claude", emptyList()))
    }

    @Test fun `an agent without any project still gets its global skills`(@org.junit.jupiter.api.io.TempDir home: java.nio.file.Path) {
        com.shutterstar.agenthub.writeSkillMd(home.resolve(".claude/skills/review"), "# Review\n")
        val discovery = ProjectEnvironmentDiscoveryService(
            skillDiscovery = com.shutterstar.agenthub.environment.skills.discovery.SkillDiscoveryService(
                listOf(com.shutterstar.agenthub.environment.skills.discovery.ClaudeSkillProvider(home)),
            ),
            mcpDiscovery = com.shutterstar.agenthub.environment.mcp.discovery.McpDiscoveryService(emptyList()),
            instructionDiscovery = com.shutterstar.agenthub.environment.instructions.discovery.InstructionDiscoveryService(emptyList()),
            configDiscovery = com.shutterstar.agenthub.environment.config.discovery.ConfigDiscoveryService(emptyList()),
            persist = { _, _ -> },
        )

        val result = AgentEnvironmentDiscoveryService(discovery, cachedEnvironments = { emptyMap() }).discover("claude", emptyList())

        assertEquals(listOf("review"), result.skills.map { it.name })
        assertEquals(SkillScope.GLOBAL, result.skills.single().scope)
    }

    private fun project(id: String, agentId: String) = com.shutterstar.agenthub.projects.model.DiscoveredProject(
        com.shutterstar.agenthub.projects.model.ProjectIdentity(id, null, null, null), id, null, null, null, null,
        listOf(com.shutterstar.agenthub.projects.model.AgentProject(agentId, id, 0, null, emptyList())), null,
    )

    private fun skill(
        id: String,
        scope: SkillScope,
        path: String,
    ): AgentSkill = AgentSkill(
        identity = SkillIdentity(id),
        name = "review",
        description = null,
        scope = scope,
        sources = listOf(SkillSource("claude", path, scope, false, "fingerprint")),
        compatibleAgents = setOf("claude"),
        consistency = SkillConsistency.SINGLE_SOURCE,
    )
}
