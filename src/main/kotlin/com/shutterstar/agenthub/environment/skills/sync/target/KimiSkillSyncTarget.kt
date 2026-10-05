package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Kimi Code CLI: the Kimi-specific skill directories are `$KIMI_CODE_HOME/skills` (default `~/.kimi-code/skills`) and `<project>/.kimi-code/skills`. */
class KimiSkillSyncTarget(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillSyncTarget {
    override val agentId: String = "kimi"

    override fun globalSkillDirectory(): Path =
        EnvHomeDirectorySupport.resolveGuarded("KIMI_CODE_HOME", userHome, ".kimi-code").resolve("skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? {
        val rawPath = project.path ?: project.gitRoot ?: return null
        val root = try {
            Path.of(rawPath)
        } catch (_: InvalidPathException) {
            return null
        }
        return root.resolve(".kimi-code").resolve("skills")
    }

    override fun supportsLinkedSkills(): Boolean = true
}
