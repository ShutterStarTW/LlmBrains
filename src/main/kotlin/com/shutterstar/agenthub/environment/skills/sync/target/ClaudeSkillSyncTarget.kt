package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.skills.discovery.ClaudeSkillProvider
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

class ClaudeSkillSyncTarget(
    userHome: Path = AgentRuntime.userHome(),
) : SkillSyncTarget by DirectorySkillSyncTarget(
    agentId = "claude",
    provider = ClaudeSkillProvider(userHome),
)
