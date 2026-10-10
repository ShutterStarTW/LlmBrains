package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * Bespoke, matching [com.shutterstar.agenthub.environment.skills.discovery.OpenCodeSkillProvider]'s
 * own bespoke path logic: OpenCode's global and project skill directories are structurally
 * different (`.config/opencode/skills` vs `.opencode/skills`), not one relative path reused for
 * both scopes like the [DirectorySkillSyncTarget]-backed agents.
 */
class OpenCodeSkillSyncTarget(
    private val userHome: Path = AgentRuntime.userHome(),
) : SkillSyncTarget {
    override val agentId: String = "opencode"

    override fun globalSkillDirectory(): Path =
        EnvHomeDirectorySupport.resolveXdgGuarded("XDG_CONFIG_HOME", userHome, ".config", "opencode").resolve("skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? {
        return ProjectPathResolver.resolveExistingRoot(project)?.resolve(".opencode")?.resolve("skills")
    }

    // Mirrors OpenCodeSkillProvider's ".claude/skills" compatibility root at both scopes (its
    // nested project-level scan for the same owner directories is discovery's job, not this
    // lightweight presence check's).
    // Also the home-level `~/.opencode` config directory, which OpenCode scans for skills as well, and the singular
    // `skill/` folders it accepts next to `skills/`.
    override fun alternateGlobalSkillDirectories(): List<Path> = listOf(
        userHome.resolve(".claude").resolve("skills"),
        userHome.resolve(".opencode").resolve("skills"),
        globalSkillDirectory().resolveSibling("skill"),
        userHome.resolve(".opencode").resolve("skill"),
    )

    override fun alternateProjectSkillDirectories(project: DiscoveredProject): List<Path> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return listOf(projectRoot.resolve(".claude").resolve("skills"), projectRoot.resolve(".opencode").resolve("skill"))
    }

    override fun supportsLinkedSkills(): Boolean = true
}
