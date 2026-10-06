package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

class OpenCodeSkillProvider(
    private val userHome: Path = AgentRuntime.userHome(),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()
    private val configRoot = EnvHomeDirectorySupport.resolveXdgGuarded(
        "XDG_CONFIG_HOME",
        userHome,
        ".config",
        OPENCODE_CONFIG_APP_NAME,
    )

    // OPENCODE_CONFIG_DIR is searched "just like the standard .opencode directory" (documented), so it can hold skills/.
    private val customConfigRoot = EnvHomeDirectorySupport.configuredDirectoryGuarded("OPENCODE_CONFIG_DIR", userHome)

    override fun discoverGlobal(): List<SkillSourceRecord> {
        val budget = scanner.newBudget()
        // Every config directory is scanned for `{skill,skills}/**/SKILL.md` (source: `skill/index.ts`); the home-level
        // `~/.opencode` counts as a config directory too (`config/paths.ts` looks for `.opencode` from the home directory).
        val configDirectories = listOfNotNull(configRoot, userHome.resolve(OPENCODE_DIRECTORY), customConfigRoot)
        return (
            configDirectories.flatMap { directory -> SKILL_DIRECTORIES.map(directory::resolve) } +
                listOf(userHome.resolve(CLAUDE_SKILLS)) // a list: a bare Path would be added as its segments
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
        val nested = listOf(OPENCODE_DIRECTORY, CLAUDE_DIRECTORY).flatMap { ownerDirectory ->
            scanner.discoverNestedProjectSkills(
                projectRoot = projectRoot,
                ownerDirectoryName = ownerDirectory,
                agentId = agentId,
                shared = false,
                projectName = project.name,
                requireValidMetadata = true,
                budget = budget,
            )
        }
        // The singular `skill/` folder is accepted next to `skills/` in `.opencode` (the nested scan reads `skills/` only).
        val singular = scanner.discover(
            root = projectRoot.resolve(OPENCODE_DIRECTORY).resolve(SINGULAR_DIRECTORY),
            agentId = agentId,
            scope = SkillScope.PROJECT,
            shared = false,
            projectName = project.name,
            requireValidMetadata = true,
            budget = budget,
        )
        return (nested + singular).distinctBy { it.path }
    }

    private companion object {
        const val AGENT_ID = "opencode"
        const val OPENCODE_DIRECTORY = ".opencode"
        const val OPENCODE_CONFIG_APP_NAME = "opencode"
        const val CLAUDE_DIRECTORY = ".claude"
        const val SKILLS_DIRECTORY = "skills"
        const val SINGULAR_DIRECTORY = "skill"
        val SKILL_DIRECTORIES = listOf(SKILLS_DIRECTORY, SINGULAR_DIRECTORY)
        val CLAUDE_SKILLS: Path = Path.of(CLAUDE_DIRECTORY, SKILLS_DIRECTORY)
    }
}
