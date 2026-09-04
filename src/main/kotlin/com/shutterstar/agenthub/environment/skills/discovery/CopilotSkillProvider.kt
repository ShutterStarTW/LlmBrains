package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

class CopilotSkillProvider(
    private val copilotDirectory: Path = defaultCopilotDirectory(),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()

    override fun discoverGlobal(): List<SkillSourceRecord> = scanner.discover(
        root = copilotDirectory.resolve(SKILLS_DIRECTORY),
        agentId = agentId,
        scope = SkillScope.GLOBAL,
        shared = false,
        projectName = null,
    )

    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return listOf(
            projectRoot.resolve(GITHUB_DIRECTORY).resolve(SKILLS_DIRECTORY),
            projectRoot.resolve(CLAUDE_DIRECTORY).resolve(SKILLS_DIRECTORY),
        ).flatMap { root ->
            scanner.discover(
                root = root,
                agentId = agentId,
                scope = SkillScope.PROJECT,
                shared = false,
                projectName = project.name,
            )
        }
    }

    private companion object {
        const val AGENT_ID = "copilot"
        const val COPILOT_DIRECTORY = ".copilot"
        const val GITHUB_DIRECTORY = ".github"
        const val CLAUDE_DIRECTORY = ".claude"
        const val SKILLS_DIRECTORY = "skills"

        fun defaultCopilotDirectory(): Path = EnvHomeDirectorySupport.resolve("COPILOT_HOME", COPILOT_DIRECTORY)
    }
}
