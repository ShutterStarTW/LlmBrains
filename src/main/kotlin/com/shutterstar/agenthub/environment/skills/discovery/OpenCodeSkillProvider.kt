package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

class OpenCodeSkillProvider(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()
    private val configRoot = EnvHomeDirectorySupport.resolveXdgGuarded(
        "XDG_CONFIG_HOME",
        userHome,
        ".config",
        OPENCODE_CONFIG_APP_NAME,
    )

    override fun discoverGlobal(): List<SkillSourceRecord> {
        val budget = scanner.newBudget()
        return listOf(
            configRoot.resolve(SKILLS_DIRECTORY),
            userHome.resolve(CLAUDE_SKILLS),
        ).flatMap { root ->
            scanner.discover(
                root = root,
                agentId = agentId,
                scope = SkillScope.GLOBAL,
                shared = false,
                projectName = null,
                requireValidMetadata = true,
                budget = budget,
            )
        }.distinctBy { it.path }
    }

    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        val budget = scanner.newBudget()
        return listOf(OPENCODE_DIRECTORY, CLAUDE_DIRECTORY).flatMap { ownerDirectory ->
            scanner.discoverNestedProjectSkills(
                projectRoot = projectRoot,
                ownerDirectoryName = ownerDirectory,
                agentId = agentId,
                shared = false,
                projectName = project.name,
                requireValidMetadata = true,
                budget = budget,
            )
        }.distinctBy { it.path }
    }

    private companion object {
        const val AGENT_ID = "opencode"
        const val OPENCODE_DIRECTORY = ".opencode"
        const val OPENCODE_CONFIG_APP_NAME = "opencode"
        const val CLAUDE_DIRECTORY = ".claude"
        const val SKILLS_DIRECTORY = "skills"
        val CLAUDE_SKILLS: Path = Path.of(CLAUDE_DIRECTORY, SKILLS_DIRECTORY)
    }
}
