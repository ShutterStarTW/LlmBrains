package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

class GrokSkillProvider(
    private val homeDirectory: Path = Path.of(System.getProperty("user.home")),
    private val grokDirectory: Path = defaultGrokDirectory(homeDirectory),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()

    override fun discoverGlobal(): List<SkillSourceRecord> {
        val budget = scanner.newBudget()
        return discoverRoots(
            roots = listOf(
                grokDirectory.resolve(SKILLS_DIRECTORY),
                grokDirectory.resolve(PLUGINS_DIRECTORY),
                homeDirectory.resolve(CLAUDE_SKILLS),
                homeDirectory.resolve(CURSOR_SKILLS),
            ),
            scope = SkillScope.GLOBAL,
            budget = budget,
        )
    }

    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        val budget = scanner.newBudget()
        val nativeSkills = listOf(GROK_DIRECTORY, CLAUDE_DIRECTORY, CURSOR_DIRECTORY).flatMap { ownerDirectory ->
            scanner.discoverNestedProjectSkills(
                projectRoot = projectRoot,
                ownerDirectoryName = ownerDirectory,
                agentId = agentId,
                shared = false,
                projectName = project.name,
                budget = budget,
            )
        }
        val pluginSkills = discoverRoots(
            roots = listOf(projectRoot.resolve(GROK_DIRECTORY).resolve(PLUGINS_DIRECTORY)),
            scope = SkillScope.PROJECT,
            projectName = project.name,
            budget = budget,
        )
        return (nativeSkills + pluginSkills).distinctBy { it.path }
    }

    private fun discoverRoots(
        roots: List<Path>,
        scope: SkillScope,
        projectName: String? = null,
        budget: SkillDirectoryScanner.ScanBudget,
    ): List<SkillSourceRecord> = roots
        .flatMap { root: Path ->
            scanner.discover(
                root = root,
                agentId = agentId,
                scope = scope,
                shared = false,
                projectName = projectName,
                requireValidMetadata = false,
                budget = budget,
            )
        }
        .distinctBy { it.path }

    private companion object {
        const val AGENT_ID = "grok"
        const val GROK_DIRECTORY = ".grok"
        const val CLAUDE_DIRECTORY = ".claude"
        const val CURSOR_DIRECTORY = ".cursor"
        const val SKILLS_DIRECTORY = "skills"
        const val PLUGINS_DIRECTORY = "plugins"
        val CLAUDE_SKILLS: Path = Path.of(CLAUDE_DIRECTORY, SKILLS_DIRECTORY)
        val CURSOR_SKILLS: Path = Path.of(CURSOR_DIRECTORY, SKILLS_DIRECTORY)

        fun defaultGrokDirectory(homeDirectory: Path): Path =
            EnvHomeDirectorySupport.resolveGuarded("GROK_HOME", homeDirectory, GROK_DIRECTORY)
    }
}
