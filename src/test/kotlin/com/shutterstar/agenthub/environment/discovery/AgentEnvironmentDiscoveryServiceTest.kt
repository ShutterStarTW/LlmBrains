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
