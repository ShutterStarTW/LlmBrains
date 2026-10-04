package com.shutterstar.agenthub.environment.skills.sync.model

import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path

interface SkillSyncTarget {
    val agentId: String

    fun globalSkillDirectory(): Path?

    fun projectSkillDirectory(project: DiscoveredProject): Path?

    fun supportsLinkedSkills(): Boolean

    /**
     * Extra roots this agent's *discovery* provider also recognizes, beyond the native root above
     * — e.g. Antigravity reads `.gemini/skills`, `.gemini/antigravity-cli/skills`, etc. in addition
     * to its native `.gemini/config/skills`. [SkillTargetObserver] checks these only to avoid
     * reporting an already-present skill as unshared in the "Share with…" checklist; synchronization
     * itself never reads or writes them — it only ever touches [globalSkillDirectory].
     */
    fun alternateGlobalSkillDirectories(): List<Path> = emptyList()

    /** Project-scope counterpart of [alternateGlobalSkillDirectories]. */
    fun alternateProjectSkillDirectories(project: DiscoveredProject): List<Path> = emptyList()
}
