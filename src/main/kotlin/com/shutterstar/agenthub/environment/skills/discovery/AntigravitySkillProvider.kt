package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.discovery.AntigravityHomeSupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class AntigravitySkillProvider(
    private val homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()

    override fun discoverGlobal(): List<SkillSourceRecord> {
        val budget = scanner.newBudget()
        val directSkills = globalRoots().flatMap { root ->
            scanner.discover(
                root = root,
                agentId = agentId,
                scope = SkillScope.GLOBAL,
                shared = false,
                projectName = null,
                requireValidMetadata = false,
                budget = budget,
            )
        }
        val pluginSkills = discoverPluginSkills(globalPluginRoots(), SkillScope.GLOBAL, projectName = null, budget = budget)
        return (directSkills + pluginSkills).distinctBy { it.path }
    }

    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        val budget = scanner.newBudget()
        val directProjectSkills = projectRoots(projectRoot).flatMap { root ->
            scanner.discover(
                root = root,
                agentId = agentId,
                scope = SkillScope.PROJECT,
                shared = false,
                projectName = project.name,
                requireValidMetadata = false,
                budget = budget,
            )
        }
        val nestedProjectSkills = PROJECT_OWNER_DIRECTORIES.flatMap { ownerDirectory ->
            scanner.discoverNestedProjectSkills(
                projectRoot = projectRoot,
                ownerDirectoryName = ownerDirectory,
                agentId = agentId,
                shared = false,
                projectName = project.name,
                requireValidMetadata = false,
                budget = budget,
            )
        }
        val pluginSkills = discoverPluginSkills(
            projectPluginRoots(projectRoot),
            SkillScope.PROJECT,
            projectName = project.name,
            budget = budget,
        )
        return (directProjectSkills + nestedProjectSkills + pluginSkills).distinctBy { it.path }
    }

    private fun discoverPluginSkills(
        pluginDirs: List<Path>,
        scope: SkillScope,
        projectName: String?,
        budget: SkillDirectoryScanner.ScanBudget,
    ): List<SkillSourceRecord> =
        AntigravityHomeSupport.forEachPluginDirectory(pluginDirs, MAX_PLUGIN_ENTRIES) { pluginDir ->
            val skillDir = pluginDir.resolve(SKILLS_DIRECTORY)
            if (Files.isDirectory(skillDir, LinkOption.NOFOLLOW_LINKS)) {
                scanner.discover(
                    root = skillDir,
                    agentId = agentId,
                    scope = scope,
                    shared = false,
                    projectName = projectName,
                    requireValidMetadata = false,
                    budget = budget,
                )
            } else {
                emptyList()
            }
        }

    private fun globalRoots(): List<Path> {
        val gemini = homeDirectory.resolve(GEMINI_DIRECTORY)
        return listOfNotNull(
            gemini.resolve(CONFIG_DIRECTORY).resolve(SKILLS_DIRECTORY),
            gemini.resolve(CLI_DIRECTORY).resolve(SKILLS_DIRECTORY),
            gemini.resolve(SKILLS_DIRECTORY),
            gemini.resolve(ANTIGRAVITY_DIRECTORY).resolve(SKILLS_DIRECTORY),
            gemini.resolve(CLI_DIRECTORY).resolve(BUILTIN_DIRECTORY).resolve(SKILLS_DIRECTORY),
            gemini.resolve(ANTIGRAVITY_DIRECTORY).resolve(BUILTIN_DIRECTORY).resolve(SKILLS_DIRECTORY),
            AntigravityHomeSupport.configuredHome()?.resolve(SKILLS_DIRECTORY),
            AntigravityHomeSupport.configuredHome()?.resolve(BUILTIN_DIRECTORY)?.resolve(SKILLS_DIRECTORY),
        ).distinct()
    }

    private fun globalPluginRoots(): List<Path> {
        val gemini = homeDirectory.resolve(GEMINI_DIRECTORY)
        return listOfNotNull(
            gemini.resolve(PLUGINS_DIRECTORY),
            gemini.resolve(CONFIG_DIRECTORY).resolve(PLUGINS_DIRECTORY),
            gemini.resolve(CLI_DIRECTORY).resolve(PLUGINS_DIRECTORY),
            gemini.resolve(ANTIGRAVITY_DIRECTORY).resolve(PLUGINS_DIRECTORY),
            AntigravityHomeSupport.configuredHome()?.resolve(PLUGINS_DIRECTORY),
        ).distinct()
    }

    private fun projectRoots(projectRoot: Path): List<Path> = listOf(
        projectRoot.resolve(LEGACY_AGENT_DIRECTORY).resolve(SKILLS_DIRECTORY),
        projectRoot.resolve(ALT_AGENTS_DIRECTORY).resolve(SKILLS_DIRECTORY),
        projectRoot.resolve(ALT_AGENT_DIRECTORY).resolve(SKILLS_DIRECTORY),
        projectRoot.resolve(GEMINI_DIRECTORY).resolve(SKILLS_DIRECTORY),
    )

    private fun projectPluginRoots(projectRoot: Path): List<Path> = listOf(
        projectRoot.resolve(AGENTS_DIRECTORY).resolve(PLUGINS_DIRECTORY),
        projectRoot.resolve(LEGACY_AGENT_DIRECTORY).resolve(PLUGINS_DIRECTORY),
        projectRoot.resolve(ALT_AGENTS_DIRECTORY).resolve(PLUGINS_DIRECTORY),
        projectRoot.resolve(ALT_AGENT_DIRECTORY).resolve(PLUGINS_DIRECTORY),
    )

    private companion object {
        const val AGENT_ID = "antigravity"
        const val GEMINI_DIRECTORY = ".gemini"
        const val CONFIG_DIRECTORY = "config"
        const val CLI_DIRECTORY = "antigravity-cli"
        const val ANTIGRAVITY_DIRECTORY = "antigravity"
        const val BUILTIN_DIRECTORY = "builtin"
        const val AGENTS_DIRECTORY = ".agents"
        const val LEGACY_AGENT_DIRECTORY = ".agent"
        const val ALT_AGENTS_DIRECTORY = "_agents"
        const val ALT_AGENT_DIRECTORY = "_agent"
        const val SKILLS_DIRECTORY = "skills"
        const val PLUGINS_DIRECTORY = "plugins"
        const val MAX_PLUGIN_ENTRIES = 128

        val PROJECT_OWNER_DIRECTORIES = listOf(
            LEGACY_AGENT_DIRECTORY,
            ALT_AGENTS_DIRECTORY,
            ALT_AGENT_DIRECTORY,
            GEMINI_DIRECTORY,
        )
    }
}
