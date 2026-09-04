package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

class ClineSkillProvider(
    private val homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()

    override fun discoverGlobal(): List<SkillSourceRecord> =
        scanner.discover(homeDirectory.resolve(".cline/skills"), agentId, SkillScope.GLOBAL, shared = false, projectName = null)

    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val root = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return listOf(
            root.resolve(".cline/skills"),
            root.resolve(".clinerules/skills"),
            root.resolve(".claude/skills"),
        ).flatMap { skillRoot ->
            scanner.discover(
                root = skillRoot,
                agentId = agentId,
                scope = SkillScope.PROJECT,
                shared = false,
                projectName = project.name,
            )
        }
    }

    private companion object {
        const val AGENT_ID = "cline"
    }
}
