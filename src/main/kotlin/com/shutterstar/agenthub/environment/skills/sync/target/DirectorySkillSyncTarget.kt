package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.skills.discovery.DirectorySkillProvider
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path

class DirectorySkillSyncTarget(
    override val agentId: String,
    private val provider: DirectorySkillProvider,
) : SkillSyncTarget {
    override fun globalSkillDirectory(): Path = provider.resolveGlobalDirectory()

    override fun projectSkillDirectory(project: DiscoveredProject): Path? =
        provider.resolveProjectDirectory(project)

    override fun supportsLinkedSkills(): Boolean = true
}
