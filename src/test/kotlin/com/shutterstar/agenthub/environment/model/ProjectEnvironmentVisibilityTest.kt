package com.shutterstar.agenthub.environment.model

import com.shutterstar.agenthub.environment.mcp.model.McpConsistency
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpServer
import com.shutterstar.agenthub.environment.mcp.model.McpSource
import com.shutterstar.agenthub.environment.mcp.model.McpTransport
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillIdentity
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ProjectEnvironmentVisibilityTest {
    @Test
    fun `persisted snapshot hides removed agent sources`() {
        val skill = AgentSkill(
            SkillIdentity("skill"), "Skill", null, SkillScope.GLOBAL,
            listOf(
                SkillSource("claude", "/claude/skill", SkillScope.GLOBAL, false, "a"),
                SkillSource("codex", "/codex/skill", SkillScope.GLOBAL, false, "b"),
            ),
            setOf("claude", "codex"), SkillConsistency.DIFFERENT,
        )
        val server = McpServer(
            "server", "Server", McpTransport.STDIO, null, emptyList(), null, emptySet(),
            listOf(McpSource("claude", "/claude/config", "Server"), McpSource("codex", "/codex/config", "Server")),
            McpScope.GLOBAL, McpConsistency.DIFFERENT,
        )
        val cached = ProjectEnvironment("project", setOf("claude", "codex"), listOf(skill), listOf(server), emptyList())

        val visible = cached.visibleTo(setOf("claude"))

        assertEquals(setOf("claude"), visible.agentIds)
        assertEquals(listOf("/claude/skill"), visible.skills.single().sources.map { it.path })
        assertEquals(setOf("claude"), visible.skills.single().compatibleAgents)
        assertEquals(listOf("/claude/config"), visible.mcpServers.single().sources.map { it.configPath })
        assertFalse(visible.toString().contains("/codex/"))
    }
}
