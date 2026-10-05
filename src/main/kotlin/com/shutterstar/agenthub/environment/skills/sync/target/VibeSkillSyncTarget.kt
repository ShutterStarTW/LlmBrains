package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Mistral Vibe: the native skill directories are `$VIBE_HOME/skills` (default `~/.vibe/skills`) and `<project>/.vibe/skills`. */
class VibeSkillSyncTarget(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillSyncTarget {
    override val agentId: String = "vibe"

    override fun globalSkillDirectory(): Path =
        EnvHomeDirectorySupport.resolveGuarded("VIBE_HOME", userHome, ".vibe").resolve("skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? {
        val rawPath = project.path ?: project.gitRoot ?: return null
        val root = try {
            Path.of(rawPath)
        } catch (_: InvalidPathException) {
            return null
        }
        return root.resolve(".vibe").resolve("skills")
    }

    override fun supportsLinkedSkills(): Boolean = true
}
