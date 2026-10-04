package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

/**
 * Bespoke, matching [com.shutterstar.agenthub.environment.skills.discovery.CopilotSkillProvider]'s
 * own bespoke path logic: global and project skill directories use different conventions
 * (`$COPILOT_HOME/skills` vs the repo's `.github/skills`), not one relative path reused for both
 * scopes like the [DirectorySkillProvider][com.shutterstar.agenthub.environment.skills.discovery.DirectorySkillProvider]-backed
 * agents. Discovery also reads a `.claude/skills` compatibility root at project scope;
 * synchronization writes only the native `.github/skills` root.
 */
class CopilotSkillSyncTarget(
    private val copilotDirectory: Path = EnvHomeDirectorySupport.resolve("COPILOT_HOME", ".copilot"),
) : SkillSyncTarget {
    override val agentId: String = "copilot"

    override fun globalSkillDirectory(): Path = copilotDirectory.resolve("skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? =
        ProjectPathResolver.resolveExistingRoot(project)?.resolve(".github")?.resolve("skills")

    // Mirrors CopilotSkillProvider's ".claude/skills" project compatibility root; no global
    // alternate exists (discovery reads a single global root, same as synchronization).
    override fun alternateProjectSkillDirectories(project: DiscoveredProject): List<Path> =
        listOfNotNull(ProjectPathResolver.resolveExistingRoot(project)?.resolve(".claude")?.resolve("skills"))

    override fun supportsLinkedSkills(): Boolean = true
}
