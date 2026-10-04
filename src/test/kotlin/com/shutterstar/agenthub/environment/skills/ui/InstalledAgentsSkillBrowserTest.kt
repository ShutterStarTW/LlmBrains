package com.shutterstar.agenthub.environment.skills.ui

import com.shutterstar.agenthub.writeSkill
import com.shutterstar.agenthub.environment.skills.discovery.ClaudeSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.CodexSkillProvider
import com.shutterstar.agenthub.environment.skills.discovery.SkillDiscoveryService
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InstalledAgentsSkillBrowserTest {
    @TempDir
    lateinit var home: Path

    @Test
    fun `hasSkills and every scope ignore skills that belong to an uninstalled agent`() {
        val root = Files.createDirectories(home.resolve("project"))
        writeSkill(root.resolve(".codex/skills/codex-skill"), "codex-skill")
        writeSkill(home.resolve(".codex/skills/global-codex"), "global-codex")
        val project = DiscoveredProject(
            ProjectIdentity("p", root.toString(), root.toString(), null),
            "p",
            root.toString(),
            root.toString(),
            null,
            null,
            emptyList(),
            null,
        )

        fun browser(vararg installed: String) = SkillBrowserDiscovery(
            SkillDiscoveryService(
                listOf(ClaudeSkillProvider(home), CodexSkillProvider(home)),
                isAgentVisible = { it in installed },
            ),
            projectsWithSkills = { listOf(project) },
        )

        val claudeOnly = browser("claude")
        assertFalse(claudeOnly.hasSkills(project))
        assertTrue(claudeOnly.discover(SkillBrowserContext(SkillScope.GLOBAL, null)).skills.isEmpty())
        assertTrue(claudeOnly.discover(SkillBrowserContext(SkillScope.PROJECT, project)).skills.isEmpty())
        assertTrue(claudeOnly.discover(SkillBrowserContext(SkillScope.PROJECT, null)).skills.isEmpty())

        val withCodex = browser("claude", "codex")
        assertTrue(withCodex.hasSkills(project))
        assertEquals(
            listOf("global-codex"),
            withCodex.discover(SkillBrowserContext(SkillScope.GLOBAL, null)).skills.map { it.name },
        )
        assertEquals(
            listOf("codex-skill"),
            withCodex.discover(SkillBrowserContext(SkillScope.PROJECT, null)).skills.map { it.name },
        )
    }

}