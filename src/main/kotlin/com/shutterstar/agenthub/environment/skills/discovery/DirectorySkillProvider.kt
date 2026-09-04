package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

abstract class DirectorySkillProvider internal constructor(
    final override val agentId: String?,
    private val userHome: Path,
    private val relativeSkillDirectory: Path,
    private val shared: Boolean,
) : SkillProvider {
    private val scanner = SkillDirectoryScanner()

    override fun discoverGlobal(): List<SkillSourceRecord> =
        scanner.discover(
            root = userHome.resolve(relativeSkillDirectory),
            agentId = agentId,
            scope = SkillScope.GLOBAL,
            shared = shared,
            projectName = null,
        )

    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()

        return scanner.discover(
            root = projectRoot.resolve(relativeSkillDirectory),
            agentId = agentId,
            scope = SkillScope.PROJECT,
            shared = shared,
            projectName = project.name,
        )
    }
}
