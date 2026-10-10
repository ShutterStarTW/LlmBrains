package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/** Mistral Vibe: the native skill directories are `$VIBE_HOME/skills` (default `~/.vibe/skills`) and `<project>/.vibe/skills`. */
class VibeSkillSyncTarget(
    private val userHome: Path = AgentRuntime.userHome(),
) : SkillSyncTarget {
    override val agentId: String = "vibe"

    override fun globalSkillDirectory(): Path =
        EnvHomeDirectorySupport.resolveGuarded("VIBE_HOME", userHome, ".vibe").resolve("skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? {
        return ProjectPathResolver.resolveExistingRoot(project)?.resolve(".vibe")?.resolve("skills")
    }

    override fun supportsLinkedSkills(): Boolean = true
}
