package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

/**
 * Mistral Vibe skills (`vibe/core/config/harness_files/_paths.py`, `vibe/core/paths/_local_config_files.py`): the global
 * `skills/` of the vibe home (`~/.vibe`, or `VIBE_HOME`) and the project's `.vibe/skills/`. The `.agents/skills` roots
 * (`~/.agents/skills`, `<project>/.agents/skills`) are the `SharedSkillProvider`'s. Vibe looks at the project root only,
 * so nested `.vibe/skills` folders are not listed; `skill_paths` of `config.toml`, plugin skills and the bundled
 * `builtin-skills` are not modelled.
 */
class VibeSkillProvider(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()
    private val vibeHome = EnvHomeDirectorySupport.resolveGuarded("VIBE_HOME", userHome, VIBE_DIRECTORY)

    override fun discoverGlobal(): List<SkillSourceRecord> = scanner.discover(
        root = vibeHome.resolve(SKILLS_DIRECTORY),
        agentId = agentId,
        scope = SkillScope.GLOBAL,
        shared = false,
        projectName = null,
        requireValidMetadata = true,
    )

    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return scanner.discover(
            root = projectRoot.resolve(VIBE_DIRECTORY).resolve(SKILLS_DIRECTORY),
            agentId = agentId,
            scope = SkillScope.PROJECT,
            shared = false,
            projectName = project.name,
            requireValidMetadata = true,
        )
    }

    private companion object {
        const val AGENT_ID = "vibe"
        const val VIBE_DIRECTORY = ".vibe"
        const val SKILLS_DIRECTORY = "skills"
    }
}
