package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * Kilo Code CLI: the documented native skill directories are `~/.kilo/skills` and `<project>/.kilo/skills`.
 * Kilo also reads `~/.config/kilo/skills`, the legacy `.kilocode/skills` and the Claude Code compatibility
 * roots, which are reported as alternates (see [com.shutterstar.agenthub.environment.skills.discovery.KiloSkillProvider]),
 * and so are the singular `skill/` folders Kilo accepts next to `skills/`.
 */
class KiloSkillSyncTarget(
    private val userHome: Path = AgentRuntime.userHome(),
) : SkillSyncTarget {
    override val agentId: String = "kilo"

    override fun globalSkillDirectory(): Path = userHome.resolve(".kilo").resolve("skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? =
        projectRoot(project)?.resolve(".kilo")?.resolve("skills")

    override fun alternateGlobalSkillDirectories(): List<Path> {
        val configDirectory = EnvHomeDirectorySupport.resolveXdgGuarded("XDG_CONFIG_HOME", userHome, ".config", "kilo")
        return listOf(
            configDirectory.resolve("skills"),
            userHome.resolve(".kilocode").resolve("skills"),
            userHome.resolve(".claude").resolve("skills"),
            userHome.resolve(".kilo").resolve("skill"),
            configDirectory.resolve("skill"),
            userHome.resolve(".kilocode").resolve("skill"),
        )
    }

    override fun alternateProjectSkillDirectories(project: DiscoveredProject): List<Path> {
        val root = projectRoot(project) ?: return emptyList()
        return listOf(
            root.resolve(".kilocode").resolve("skills"),
            root.resolve(".claude").resolve("skills"),
            root.resolve(".kilo").resolve("skill"),
            root.resolve(".kilocode").resolve("skill"),
        )
    }

    override fun supportsLinkedSkills(): Boolean = true

    private fun projectRoot(project: DiscoveredProject): Path? = ProjectPathResolver.resolveExistingRoot(project)
}
