package com.shutterstar.agenthub.environment.skills.discovery

import java.nio.file.Path

class KiroSkillProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : DirectorySkillProvider(
    agentId = "kiro",
    userHome = homeDirectory,
    relativeSkillDirectory = Path.of(".kiro", "skills"),
    shared = false,
)
