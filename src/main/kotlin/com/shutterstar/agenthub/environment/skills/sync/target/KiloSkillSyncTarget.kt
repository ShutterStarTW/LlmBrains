package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Kilo Code CLI: the documented native skill directories are `~/.kilo/skills` and `<project>/.kilo/skills`.
 * Kilo also reads `~/.config/kilo/skills`, the legacy `.kilocode/skills` and the Claude Code compatibility
 * roots, which are reported as alternates (see [com.shutterstar.agenthub.environment.skills.discovery.KiloSkillProvider]).
 */
class KiloSkillSyncTarget(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillSyncTarget {
    override val agentId: String = "kilo"

    override fun globalSkillDirectory(): Path = userHome.resolve(".kilo").resolve("skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? =
        projectRoot(project)?.resolve(".kilo")?.resolve("skills")

    override fun alternateGlobalSkillDirectories(): List<Path> = listOf(
        EnvHomeDirectorySupport.resolveXdgGuarded("XDG_CONFIG_HOME", userHome, ".config", "kilo").resolve("skills"),
        userHome.resolve(".kilocode").resolve("skills"),
        userHome.resolve(".claude").resolve("skills"),
    )

    override fun alternateProjectSkillDirectories(project: DiscoveredProject): List<Path> {
        val root = projectRoot(project) ?: return emptyList()
        return listOf(root.resolve(".kilocode").resolve("skills"), root.resolve(".claude").resolve("skills"))
    }

    override fun supportsLinkedSkills(): Boolean = true

    private fun projectRoot(project: DiscoveredProject): Path? {
        val rawPath = project.path ?: project.gitRoot ?: return null
        return try {
            Path.of(rawPath)
        } catch (_: InvalidPathException) {
            null
        }
    }
}
