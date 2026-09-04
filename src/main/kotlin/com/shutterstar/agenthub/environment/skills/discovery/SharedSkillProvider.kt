package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

class SharedSkillProvider(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillProvider {
    override val agentId: String? = null
    private val scanner = SkillDirectoryScanner()

    override fun discoverGlobal(): List<SkillSourceRecord> =
        scanner.discover(
            root = userHome.resolve(RELATIVE_SKILL_DIRECTORY),
            agentId = agentId,
            scope = SkillScope.GLOBAL,
            shared = true,
            projectName = null,
        )

    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return scanner.discoverNestedProjectSkills(
            projectRoot = projectRoot,
            ownerDirectoryName = AGENTS_DIRECTORY,
            agentId = agentId,
            shared = true,
            projectName = project.name,
        )
    }

    private companion object {
        const val AGENTS_DIRECTORY = ".agents"
        val RELATIVE_SKILL_DIRECTORY: Path = Path.of(AGENTS_DIRECTORY, "skills")
    }
}
