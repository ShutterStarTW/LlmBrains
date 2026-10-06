package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

class CursorSkillSyncTarget(
    private val userHome: Path = AgentRuntime.userHome(),
) : SkillSyncTarget {
    override val agentId: String = "cursor"

    // Discovery also reads compatibility/plugin roots; synchronization writes only the native root.
    override fun globalSkillDirectory(): Path = userHome.resolve(".cursor/skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? =
        ProjectPathResolver.resolveExistingRoot(project)?.resolve(".cursor/skills")

    // Mirrors CursorSkillProvider's compatibility roots (excluding the dynamic plugin roots, which
    // require enumerating installed plugins rather than a fixed list).
    override fun alternateGlobalSkillDirectories(): List<Path> = listOf(
        userHome.resolve(".cursor/skills-cursor"),
        userHome.resolve(".claude/skills"),
        userHome.resolve(".codex/skills"),
    )

    override fun alternateProjectSkillDirectories(project: DiscoveredProject): List<Path> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return listOf(
            projectRoot.resolve(".claude/skills"),
            projectRoot.resolve(".codex/skills"),
        )
    }

    override fun supportsLinkedSkills(): Boolean = true
}
