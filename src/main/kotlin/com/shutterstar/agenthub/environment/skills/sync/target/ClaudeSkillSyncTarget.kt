package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.skills.discovery.ClaudeSkillProvider
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import java.nio.file.Path

class ClaudeSkillSyncTarget(
    userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillSyncTarget by DirectorySkillSyncTarget(
    agentId = "claude",
    provider = ClaudeSkillProvider(userHome),
)
