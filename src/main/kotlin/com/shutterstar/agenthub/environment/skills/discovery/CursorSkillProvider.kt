package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.discovery.CursorPluginDiscoverySupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Files
import java.nio.file.Path

class CursorSkillProvider(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()

    override fun discoverGlobal(): List<SkillSourceRecord> {
        val budget = scanner.newBudget()
        return (
            discoverRoots(
                roots = listOf(
                    userHome.resolve(CURSOR_SKILLS),
                    userHome.resolve(CURSOR_MANAGED_SKILLS),
                    userHome.resolve(CLAUDE_SKILLS),
                    userHome.resolve(CODEX_SKILLS),
                ),
                scope = SkillScope.GLOBAL,
                budget = budget,
            ) +
                discoverPluginSkills(budget)
            ).distinctBy { it.path }
    }

    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        val budget = scanner.newBudget()
        val nativeSkills = scanner.discoverNestedProjectSkills(
            projectRoot = projectRoot,
            ownerDirectoryName = CURSOR_DIRECTORY,
            agentId = agentId,
            shared = false,
            projectName = project.name,
            requireValidMetadata = true,
            budget = budget,
        )
        val compatibilitySkills = discoverRoots(
            roots = listOf(
                projectRoot.resolve(CLAUDE_SKILLS),
                projectRoot.resolve(CODEX_SKILLS),
            ),
            scope = SkillScope.PROJECT,
            projectName = project.name,
            budget = budget,
        )
        return (nativeSkills + compatibilitySkills).distinctBy { it.path }
    }

    private fun discoverRoots(
        roots: List<Path>,
        scope: SkillScope,
        projectName: String? = null,
        budget: SkillDirectoryScanner.ScanBudget = scanner.newBudget(),
    ): List<SkillSourceRecord> = roots
        .flatMap { root ->
            scanner.discover(
                root = root,
                agentId = agentId,
                scope = scope,
                shared = false,
                projectName = projectName,
                requireValidMetadata = true,
                budget = budget,
            )
        }
        .distinctBy { it.path }

    private fun discoverPluginSkills(
        budget: SkillDirectoryScanner.ScanBudget,
    ): List<SkillSourceRecord> {
        val roots = CursorPluginDiscoverySupport.discover(userHome)
            .asSequence()
            .flatMap { plugin ->
                val configured = plugin.cursorPlugin && plugin.manifest.fields.containsKey(SKILLS_FIELD)
                val pluginRoots = CursorPluginDiscoverySupport.componentPaths(
                    plugin,
                    SKILLS_FIELD,
                    SKILLS_DIRECTORY,
                ).toMutableList()
                if (
                    plugin.cursorPlugin &&
                    !configured &&
                    pluginRoots.none { Files.isDirectory(it) } &&
                    Files.isRegularFile(plugin.root.resolve(SKILL_FILE))
                ) {
                    pluginRoots.add(plugin.root)
                }
                pluginRoots.asSequence()
            }
            .take(MAXIMUM_PLUGIN_COMPONENTS)
            .toList()
        return discoverRoots(roots, SkillScope.GLOBAL, budget = budget)
    }

    private companion object {
        const val AGENT_ID = "cursor"
        const val CURSOR_DIRECTORY = ".cursor"
        const val SKILLS_DIRECTORY = "skills"
        const val SKILLS_FIELD = "skills"
        const val SKILL_FILE = "SKILL.md"
        const val MAXIMUM_PLUGIN_COMPONENTS = 512
        val CURSOR_SKILLS: Path = Path.of(CURSOR_DIRECTORY, "skills")
        val CURSOR_MANAGED_SKILLS: Path = Path.of(CURSOR_DIRECTORY, "skills-cursor")
        val CLAUDE_SKILLS: Path = Path.of(".claude", "skills")
        val CODEX_SKILLS: Path = Path.of(".codex", "skills")
    }
}
