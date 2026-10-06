package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.discovery.AntigravityHomeSupport
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * Bespoke, like [CopilotSkillSyncTarget]/[CursorSkillSyncTarget]/[OpenCodeSkillSyncTarget]:
 * discovery scans many compatibility roots for Antigravity (`.gemini/config`,
 * `.gemini/antigravity-cli`, `.gemini/antigravity`, several `.agent`/`.agents`/`_agent`/`_agents`
 * project variants, plus plugin directories) — synchronization writes only the first, most
 * specific root discovery itself checks first: `~/.gemini/config/skills` globally (honoring
 * `$ANTIGRAVITY_HOME`/`$GEMINI_HOME` exactly like discovery does), `<project>/.agent/skills` for
 * a project.
 */
class AntigravitySkillSyncTarget(
    private val homeDirectory: Path = AgentRuntime.userHome(),
) : SkillSyncTarget {
    override val agentId: String = "antigravity"

    override fun globalSkillDirectory(): Path =
        (AntigravityHomeSupport.configuredHome() ?: homeDirectory.resolve(".gemini").resolve("config"))
            .resolve("skills")

    override fun projectSkillDirectory(project: DiscoveredProject): Path? =
        ProjectPathResolver.resolveExistingRoot(project)?.resolve(".agent")?.resolve("skills")

    // Mirrors AntigravitySkillProvider.globalRoots()/projectRoots(), minus the native root above
    // and the dynamic plugin roots (those require enumerating installed plugins, not a fixed list).
    override fun alternateGlobalSkillDirectories(): List<Path> {
        val gemini = homeDirectory.resolve(".gemini")
        return listOf(
            gemini.resolve("antigravity-cli").resolve("skills"),
            gemini.resolve("skills"),
            gemini.resolve("antigravity").resolve("skills"),
            gemini.resolve("antigravity-cli").resolve("builtin").resolve("skills"),
            gemini.resolve("antigravity").resolve("builtin").resolve("skills"),
        )
    }

    override fun alternateProjectSkillDirectories(project: DiscoveredProject): List<Path> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        return listOf(
            projectRoot.resolve("_agents").resolve("skills"),
            projectRoot.resolve("_agent").resolve("skills"),
            projectRoot.resolve(".gemini").resolve("skills"),
        )
    }

    override fun supportsLinkedSkills(): Boolean = true
}
