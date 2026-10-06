package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.skills.discovery.CodexSkillProvider
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

class CodexSkillSyncTarget(
    userHome: Path = AgentRuntime.userHome(),
) : SkillSyncTarget by DirectorySkillSyncTarget(
    agentId = "codex",
    provider = CodexSkillProvider(userHome),
)
