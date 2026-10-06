package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.discovery.MimoHomeSupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * MiMo Code CLI skills (source `packages/cli/src/skill/index.ts`): every `SKILL.md` below `skill/` and `skills/` in each
 * `.mimocode` config directory — the global config directory (`MIMOCODE_HOME/config`, or `$XDG_CONFIG_HOME/mimocode`),
 * `$MIMOCODE_CONFIG_DIR` and the project's `.mimocode/`. The open-standard `.agents/skills` roots are the
 * `SharedSkillProvider`'s (MiMo reads them unless `MIMOCODE_DISABLE_AGENTS_SKILLS` is set). The `.claude`, `.codex` and
 * `.opencode` compatibility roots are off by default (`MIMOCODE_ENABLE_*_SKILLS`) and are not listed; built-in skills
 * are not modelled.
 */
class MimoSkillProvider(
    private val userHome: Path = AgentRuntime.userHome(),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()

    private val globalRoots: List<Path> = listOfNotNull(
        MimoHomeSupport.configDirectory(userHome),
        EnvHomeDirectorySupport.configuredDirectoryGuarded("MIMOCODE_CONFIG_DIR", userHome),
    ).flatMap { directory -> SKILL_DIRECTORIES.map(directory::resolve) }

    override fun discoverGlobal(): List<SkillSourceRecord> {
        val budget = scanner.newBudget()
        return globalRoots.flatMap { root ->
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
        val nested = scanner.discoverNestedProjectSkills(
            projectRoot = projectRoot,
            ownerDirectoryName = MIMO_DIRECTORY,
            agentId = agentId,
            shared = false,
            projectName = project.name,
            requireValidMetadata = true,
            budget = budget,
        )
        // The singular `skill/` folder is accepted next to `skills/` (the nested scan reads `skills/` only).
        val singular = scanner.discover(
            root = projectRoot.resolve(MIMO_DIRECTORY).resolve(SINGULAR_DIRECTORY),
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
        const val AGENT_ID = "mimo"
        const val MIMO_DIRECTORY = ".mimocode"
        const val SINGULAR_DIRECTORY = "skill"
        val SKILL_DIRECTORIES = listOf("skills", SINGULAR_DIRECTORY)
    }
}
