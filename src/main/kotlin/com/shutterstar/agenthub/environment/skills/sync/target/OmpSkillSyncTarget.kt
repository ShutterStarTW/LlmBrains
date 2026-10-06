package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.discovery.OmpHomeSupport
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.InvalidPathException
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * Oh My Pi: the native skill directories are `<agent dir>/skills` (default `~/.omp/agent/skills`) and
 * `<project>/.omp/skills`; the foreign project roots OMP also loads are reported as alternates.
 */
class OmpSkillSyncTarget(
    private val userHome: Path = AgentRuntime.userHome(),
) : SkillSyncTarget {
    override val agentId: String = "omp"

    override fun globalSkillDirectory(): Path = OmpHomeSupport.agentDirectory(userHome).resolve("skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? =
        projectRoot(project)?.resolve(".omp")?.resolve("skills")

    override fun alternateProjectSkillDirectories(project: DiscoveredProject): List<Path> {
        val root = projectRoot(project) ?: return emptyList()
        return listOf(root.resolve(".claude").resolve("skills"), root.resolve(".codex").resolve("skills"))
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
