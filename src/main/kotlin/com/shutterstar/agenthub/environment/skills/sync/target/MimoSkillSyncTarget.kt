package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.discovery.MimoHomeSupport
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.InvalidPathException
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * MiMo Code CLI: skills live in `skills/` of its config directory (`MIMOCODE_HOME/config`, or
 * `$XDG_CONFIG_HOME/mimocode`) and in `<project>/.mimocode/skills`. The singular `skill/` folders are also read and are
 * reported as alternates (see [com.shutterstar.agenthub.environment.skills.discovery.MimoSkillProvider]).
 */
class MimoSkillSyncTarget(
    private val userHome: Path = AgentRuntime.userHome(),
) : SkillSyncTarget {
    override val agentId: String = "mimo"

    override fun globalSkillDirectory(): Path = MimoHomeSupport.configDirectory(userHome).resolve("skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? =
        projectRoot(project)?.resolve(".mimocode")?.resolve("skills")

    override fun alternateGlobalSkillDirectories(): List<Path> = listOf(MimoHomeSupport.configDirectory(userHome).resolve("skill"))

    override fun alternateProjectSkillDirectories(project: DiscoveredProject): List<Path> =
        listOfNotNull(projectRoot(project)?.resolve(".mimocode")?.resolve("skill"))

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
