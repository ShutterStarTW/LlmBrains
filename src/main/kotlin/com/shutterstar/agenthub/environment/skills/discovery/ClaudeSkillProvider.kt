package com.shutterstar.agenthub.environment.skills.discovery

import java.nio.file.Path

class ClaudeSkillProvider(
    userHome: Path = Path.of(System.getProperty("user.home")),
) : DirectorySkillProvider(
    agentId = "claude",
    userHome = userHome,
    relativeSkillDirectory = Path.of(".claude", "skills"),
    shared = false,
)
