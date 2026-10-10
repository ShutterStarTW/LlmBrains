package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/** Junie CLI: the native skill directories are `$JUNIE_HOME/skills` (default `~/.junie/skills`) and `<project>/.junie/skills`. */
class JunieSkillSyncTarget(
    private val userHome: Path = AgentRuntime.userHome(),
) : SkillSyncTarget {
    override val agentId: String = "junie"

    override fun globalSkillDirectory(): Path =
        EnvHomeDirectorySupport.resolveGuarded("JUNIE_HOME", userHome, ".junie").resolve("skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? {
        return ProjectPathResolver.resolveExistingRoot(project)?.resolve(".junie")?.resolve("skills")
    }

    override fun supportsLinkedSkills(): Boolean = true
}
