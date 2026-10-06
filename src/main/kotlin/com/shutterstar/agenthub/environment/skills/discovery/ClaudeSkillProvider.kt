package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * Claude Code caches skills synced from the user's Claude.ai account under `skills/synced/<bucket>/`
 * (a mix of Anthropic's own built-in skills - docx, pdf, computer-use, etc. - and the user's own
 * skills mirrored back down) - not something the user created locally, so it's scanned separately
 * and flagged [system][com.shutterstar.agenthub.environment.skills.discovery.SkillSourceRecord.system],
 * distinct from the plain top-level entries in `skills/` (which the main scan below excludes it from,
 * to avoid discovering the same skill twice under two different flags).
 */
class ClaudeSkillProvider(
    userHome: Path = AgentRuntime.userHome(),
) : DirectorySkillProvider(
    agentId = "claude",
    userHome = userHome,
    relativeSkillDirectory = Path.of(".claude", "skills"),
    shared = false,
    globalSkillDirectory = EnvHomeDirectorySupport.resolveGuarded("CLAUDE_CONFIG_DIR", userHome, ".claude").resolve("skills"),
) {
    private val scanner = SkillDirectoryScanner()

    override fun discoverGlobal(): List<SkillSourceRecord> {
        val root = resolveGlobalDirectory()
        val own = scanner.discover(
            root = root,
            agentId = agentId,
            scope = SkillScope.GLOBAL,
            shared = false,
            projectName = null,
            excludeDirectoryNames = setOf(SYNCED_DIRECTORY),
        )
        val synced = scanner.discover(
            root = root.resolve(SYNCED_DIRECTORY),
            agentId = agentId,
            scope = SkillScope.GLOBAL,
            shared = false,
            projectName = null,
            system = true,
        )
        return (own + synced).distinctBy { it.path }
    }

    /**
     * Claude Code also loads nested `<subdir>/.claude/skills` (monorepo packages): sessions started in or
     * below that directory load them directly, a session above it loads them once Claude works on files there.
     */
    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return scanner.discoverNestedProjectSkills(
            projectRoot = projectRoot,
            ownerDirectoryName = CLAUDE_DIRECTORY,
            agentId = agentId,
            shared = false,
            projectName = project.name,
        )
    }

    private companion object {
        const val CLAUDE_DIRECTORY = ".claude"
        const val SYNCED_DIRECTORY = "synced"
    }
}
