package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.skills.discovery.KiroSkillProvider
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

class KiroSkillSyncTarget(
    userHome: Path = AgentRuntime.userHome(),
) : SkillSyncTarget by DirectorySkillSyncTarget(
    agentId = "kiro",
    provider = KiroSkillProvider(userHome),
)
