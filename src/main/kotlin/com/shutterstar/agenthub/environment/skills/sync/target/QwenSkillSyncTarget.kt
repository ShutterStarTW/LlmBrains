package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.skills.discovery.QwenSkillProvider
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

class QwenSkillSyncTarget(
    userHome: Path = AgentRuntime.userHome(),
) : SkillSyncTarget by DirectorySkillSyncTarget(
    agentId = "qwen",
    provider = QwenSkillProvider(userHome),
)
