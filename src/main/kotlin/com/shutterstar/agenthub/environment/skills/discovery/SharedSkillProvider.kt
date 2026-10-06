package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

class SharedSkillProvider(
    private val userHome: Path = AgentRuntime.userHome(),
) : SkillProvider {
    override val agentId: String? = null
    private val scanner = SkillDirectoryScanner()

    internal fun resolveGlobalDirectory(): Path = userHome.resolve(RELATIVE_SKILL_DIRECTORY)

    internal fun resolveProjectDirectory(project: DiscoveredProject): Path? =
        ProjectPathResolver.resolveExistingRoot(project)?.resolve(RELATIVE_SKILL_DIRECTORY)

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
