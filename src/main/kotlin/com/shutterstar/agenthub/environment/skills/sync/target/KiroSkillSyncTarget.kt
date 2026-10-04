package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.skills.discovery.KiroSkillProvider
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import java.nio.file.Path

class KiroSkillSyncTarget(
    userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillSyncTarget by DirectorySkillSyncTarget(
    agentId = "kiro",
    provider = KiroSkillProvider(userHome),
)
