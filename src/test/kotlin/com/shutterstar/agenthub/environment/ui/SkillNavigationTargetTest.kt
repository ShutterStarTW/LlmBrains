package com.shutterstar.agenthub.environment.ui

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import com.shutterstar.agenthub.projects.ui.DetailsScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SkillNavigationTargetTest {
    private val project = DiscoveredProject(
        ProjectIdentity("p", "K:/work", null, null),
        "Work", "K:/work", null, null, null, emptyList(), null,
    )

    @Test
    fun `global skill in project environment opens global scope without project filter`() {
        val row = ComparisonRow(
            category = "Skill", name = "Claude in Chrome (Global)", agentIds = setOf("claude"),
            skillId = "chrome", skillScope = SkillScope.GLOBAL, sourcePath = "K:/home/.claude/skills/chrome",
        )

        assertEquals(
            SkillNavigationTarget(SkillScope.GLOBAL, "chrome", row.sourcePath, null),
            skillNavigationTarget(row, DetailsScope.ForProject(project)),
        )
    }

    @Test
    fun `project skill keeps its project context`() {
        val row = ComparisonRow(
            category = "Skill", name = "Review (Project)", agentIds = setOf("claude"),
            skillId = "review", skillScope = SkillScope.PROJECT, sourcePath = "K:/work/.claude/skills/review",
        )

        assertEquals(
            SkillNavigationTarget(SkillScope.PROJECT, "review", row.sourcePath, project),
            skillNavigationTarget(row, DetailsScope.ForProject(project)),
        )
    }

    @Test
    fun `non skill row has no skill destination`() {
        assertNull(skillNavigationTarget(ComparisonRow("MCP", "server", emptySet()), DetailsScope.ForProject(project)))
    }
}