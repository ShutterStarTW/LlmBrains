package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Bespoke, matching [com.shutterstar.agenthub.environment.skills.discovery.OpenCodeSkillProvider]'s
 * own bespoke path logic: OpenCode's global and project skill directories are structurally
 * different (`.config/opencode/skills` vs `.opencode/skills`), not one relative path reused for
 * both scopes like the [DirectorySkillSyncTarget]-backed agents.
 */
class OpenCodeSkillSyncTarget(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillSyncTarget {
    override val agentId: String = "opencode"

    override fun globalSkillDirectory(): Path =
        EnvHomeDirectorySupport.resolveXdgGuarded("XDG_CONFIG_HOME", userHome, ".config", "opencode").resolve("skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? {
        val rawPath = project.path ?: project.gitRoot ?: return null
        val projectRoot = try {
            Path.of(rawPath)
        } catch (_: InvalidPathException) {
            return null
        }
        return projectRoot.resolve(".opencode").resolve("skills")
    }

    // Mirrors OpenCodeSkillProvider's ".claude/skills" compatibility root at both scopes (its
    // nested project-level scan for the same owner directories is discovery's job, not this
    // lightweight presence check's).
    override fun alternateGlobalSkillDirectories(): List<Path> = listOf(userHome.resolve(".claude").resolve("skills"))

    override fun alternateProjectSkillDirectories(project: DiscoveredProject): List<Path> {
        val rawPath = project.path ?: project.gitRoot ?: return emptyList()
        val projectRoot = try {
            Path.of(rawPath)
        } catch (_: InvalidPathException) {
            return emptyList()
        }
        return listOf(projectRoot.resolve(".claude").resolve("skills"))
    }

    override fun supportsLinkedSkills(): Boolean = true
}
