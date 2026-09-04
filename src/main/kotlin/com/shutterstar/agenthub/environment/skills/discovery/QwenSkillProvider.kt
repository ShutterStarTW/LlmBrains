package com.shutterstar.agenthub.environment.skills.discovery

import java.nio.file.Path

class QwenSkillProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : DirectorySkillProvider(
    agentId = "qwen",
    userHome = homeDirectory,
    relativeSkillDirectory = Path.of(".qwen", "skills"),
    shared = false,
)
