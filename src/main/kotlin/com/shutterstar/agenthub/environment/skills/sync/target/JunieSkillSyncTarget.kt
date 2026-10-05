package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Junie CLI: the native skill directories are `$JUNIE_HOME/skills` (default `~/.junie/skills`) and `<project>/.junie/skills`. */
class JunieSkillSyncTarget(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillSyncTarget {
    override val agentId: String = "junie"

    override fun globalSkillDirectory(): Path =
        EnvHomeDirectorySupport.resolveGuarded("JUNIE_HOME", userHome, ".junie").resolve("skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? {
        val rawPath = project.path ?: project.gitRoot ?: return null
        val root = try {
            Path.of(rawPath)
        } catch (_: InvalidPathException) {
            return null
        }
        return root.resolve(".junie").resolve("skills")
    }

    override fun supportsLinkedSkills(): Boolean = true
}
