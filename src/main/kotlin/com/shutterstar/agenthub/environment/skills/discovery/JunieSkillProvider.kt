package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

/**
 * Junie CLI skills (docs: junie.jetbrains.com/docs/agent-skills.html): `~/.junie/skills/` (`JUNIE_HOME` replaces the
 * `~/.junie` directory) and `<project>/.junie/skills/`; a project skill of the same name wins over the user one. The
 * `.agents/skills` roots are the `SharedSkillProvider`'s. Folders from `JUNIE_SKILL_LOCATIONS` / `--skill-location`,
 * extension-provided and built-in skills are not modelled.
 */
class JunieSkillProvider(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()
    private val junieHome = EnvHomeDirectorySupport.resolveGuarded("JUNIE_HOME", userHome, JUNIE_DIRECTORY)

    override fun discoverGlobal(): List<SkillSourceRecord> = scanner.discover(
        root = junieHome.resolve(SKILLS_DIRECTORY),
        agentId = agentId,
        scope = SkillScope.GLOBAL,
        shared = false,
        projectName = null,
        requireValidMetadata = true,
    )

    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return scanner.discover(
            root = projectRoot.resolve(JUNIE_DIRECTORY).resolve(SKILLS_DIRECTORY),
            agentId = agentId,
            scope = SkillScope.PROJECT,
            shared = false,
            projectName = project.name,
            requireValidMetadata = true,
        )
    }

    private companion object {
        const val AGENT_ID = "junie"
        const val JUNIE_DIRECTORY = ".junie"
        const val SKILLS_DIRECTORY = "skills"
    }
}
