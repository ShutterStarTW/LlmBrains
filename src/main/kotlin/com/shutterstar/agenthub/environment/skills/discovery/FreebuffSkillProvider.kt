package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * Freebuff skills (Codebuff source `sdk/src/skills/load-skills.ts`): `<skillsDir>/<name>/SKILL.md` under `<cwd>/.claude/skills`,
 * `<cwd>/.agents/skills` and, for the CLI, `~/.claude/skills` and `~/.agents/skills`. Freebuff has no skill directory of
 * its own: the `.agents/skills` roots are the `SharedSkillProvider`'s, and this provider lists the Claude Code
 * compatibility roots. (There is no sync target — Share goes through the shared folder.)
 */
class FreebuffSkillProvider(
    private val userHome: Path = AgentRuntime.userHome(),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()

    override fun discoverGlobal(): List<SkillSourceRecord> = scanner.discover(
        root = userHome.resolve(CLAUDE_DIRECTORY).resolve(SKILLS_DIRECTORY),
        agentId = agentId,
        scope = SkillScope.GLOBAL,
        shared = false,
        projectName = null,
        requireValidMetadata = true,
    )

    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return scanner.discover(
            root = projectRoot.resolve(CLAUDE_DIRECTORY).resolve(SKILLS_DIRECTORY),
            agentId = agentId,
            scope = SkillScope.PROJECT,
            shared = false,
            projectName = project.name,
            requireValidMetadata = true,
        )
    }

    private companion object {
        const val AGENT_ID = "freebuff"
        const val CLAUDE_DIRECTORY = ".claude"
        const val SKILLS_DIRECTORY = "skills"
    }
}
