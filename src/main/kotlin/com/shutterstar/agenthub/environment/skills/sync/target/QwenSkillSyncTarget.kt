package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.skills.discovery.QwenSkillProvider
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import java.nio.file.Path

class QwenSkillSyncTarget(
    userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillSyncTarget by DirectorySkillSyncTarget(
    agentId = "qwen",
    provider = QwenSkillProvider(userHome),
)
