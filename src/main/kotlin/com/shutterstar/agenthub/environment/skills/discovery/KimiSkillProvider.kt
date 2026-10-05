package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

/**
 * Kimi Code CLI skills (docs: `customization/skills.md`): the Kimi-specific `skills/` of the data root
 * (`~/.kimi-code`, or `KIMI_CODE_HOME`) and `<project>/.kimi-code/skills`. The generic `~/.agents/skills` and
 * `.agents/skills` roots are the `SharedSkillProvider`'s. Flat top-level `<name>.md` skills, `extra_skill_dirs`
 * from the config and the built-in skills are not modelled.
 */
class KimiSkillProvider(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()
    private val dataDirectory = EnvHomeDirectorySupport.resolveGuarded("KIMI_CODE_HOME", userHome, DATA_DIRECTORY)

    override fun discoverGlobal(): List<SkillSourceRecord> = scanner.discover(
        root = dataDirectory.resolve(SKILLS_DIRECTORY),
        agentId = agentId,
        scope = SkillScope.GLOBAL,
        shared = false,
        projectName = null,
        requireValidMetadata = true,
    )

    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return scanner.discover(
            root = projectRoot.resolve(DATA_DIRECTORY).resolve(SKILLS_DIRECTORY),
            agentId = agentId,
            scope = SkillScope.PROJECT,
            shared = false,
            projectName = project.name,
            requireValidMetadata = true,
        )
    }

    private companion object {
        const val AGENT_ID = "kimi"
        const val DATA_DIRECTORY = ".kimi-code"
        const val SKILLS_DIRECTORY = "skills"
    }
}
