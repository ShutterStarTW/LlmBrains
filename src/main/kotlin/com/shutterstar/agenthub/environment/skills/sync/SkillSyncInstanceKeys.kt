package com.shutterstar.agenthub.environment.skills.sync

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/** Shared by [SkillSyncRequestPlanner] and [SkillSyncOperationRunner] so both derive identical keys. */
internal fun resolveInstanceKey(
    skillId: String,
    scope: SkillScope,
    project: DiscoveredProject?,
    canonicalPath: Path,
    runtimeId: String,
): SkillInstanceKey {
    val projectRoot = project?.let { discovered ->
        (discovered.path ?: discovered.gitRoot)?.takeIf(String::isNotBlank)
            // In WSL mode the recorded path is a Linux path; the key uses the host path the files are reached by.
            ?.let(AgentRuntime::toHostPath)?.let(Path::of)
    }
    return SkillInstanceKey.host(skillId, scope, canonicalPath, projectRoot, runtimeId)
}
