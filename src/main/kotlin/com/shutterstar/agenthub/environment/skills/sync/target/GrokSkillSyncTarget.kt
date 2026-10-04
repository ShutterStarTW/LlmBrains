package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

/**
 * Discovery also reads `.claude/skills` and `.cursor/skills` compatibility roots (plus a
 * `.grok/plugins` tree); synchronization writes only the native `.grok/skills` root (global and
 * project).
 */
class GrokSkillSyncTarget(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillSyncTarget by DirectorySkillSyncTarget(
    agentId = "grok",
    provider = NativeSkillDirectoryProvider("grok", userHome, Path.of(".grok", "skills")),
) {
    // Mirrors GrokSkillProvider's static compatibility roots; the dynamic ".grok/plugins" tree is
    // excluded, same as other agents' plugin roots (it requires enumerating installed plugins).
    override fun alternateGlobalSkillDirectories(): List<Path> = listOf(
        userHome.resolve(".claude").resolve("skills"),
        userHome.resolve(".cursor").resolve("skills"),
    )

    override fun alternateProjectSkillDirectories(project: DiscoveredProject): List<Path> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return listOf(
            projectRoot.resolve(".claude").resolve("skills"),
            projectRoot.resolve(".cursor").resolve("skills"),
        )
    }
}
