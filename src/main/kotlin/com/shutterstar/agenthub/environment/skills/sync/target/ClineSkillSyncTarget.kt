package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

/**
 * Discovery also reads `.clinerules/skills` and a `.claude/skills` compatibility root at project
 * scope; synchronization writes only the native `.cline/skills` root (global and project).
 */
class ClineSkillSyncTarget(
    userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillSyncTarget by DirectorySkillSyncTarget(
    agentId = "cline",
    provider = NativeSkillDirectoryProvider("cline", userHome, Path.of(".cline", "skills")),
) {
    // No global compatibility root exists for Cline (ClineSkillProvider.discoverGlobal() reads
    // only the native root); the extra roots are project-scope only.
    override fun alternateProjectSkillDirectories(project: DiscoveredProject): List<Path> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return listOf(
            projectRoot.resolve(".clinerules").resolve("skills"),
            projectRoot.resolve(".claude").resolve("skills"),
        )
    }
}
