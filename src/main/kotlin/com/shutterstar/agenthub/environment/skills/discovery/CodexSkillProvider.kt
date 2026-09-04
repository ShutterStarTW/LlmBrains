package com.shutterstar.agenthub.environment.skills.discovery

import java.nio.file.Path

/** Discovers the legacy Codex-specific skill location; shared `.agents/skills` uses [SharedSkillProvider]. */
class CodexSkillProvider(
    userHome: Path = Path.of(System.getProperty("user.home")),
) : DirectorySkillProvider(
    agentId = "codex",
    userHome = userHome,
    relativeSkillDirectory = Path.of(".codex", "skills"),
    shared = false,
)
