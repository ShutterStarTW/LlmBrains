package com.shutterstar.agenthub.environment.skills.sync.target

import com.shutterstar.agenthub.environment.skills.discovery.CodexSkillProvider
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import java.nio.file.Path

class CodexSkillSyncTarget(
    userHome: Path = Path.of(System.getProperty("user.home")),
) : SkillSyncTarget by DirectorySkillSyncTarget(
    agentId = "codex",
    provider = CodexSkillProvider(userHome),
)
