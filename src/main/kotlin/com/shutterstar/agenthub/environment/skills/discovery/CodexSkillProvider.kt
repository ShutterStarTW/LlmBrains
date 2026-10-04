package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import java.nio.file.Path

/**
 * Discovers the legacy Codex-specific skill location; shared `.agents/skills` uses [SharedSkillProvider].
 *
 * Codex also ships its own vendor skills under `skills/.system/` (marked with a
 * `.codex-system-skills.marker` file) - dot-prefixed, so [SkillDirectoryScanner]'s normal dot-directory
 * skip already keeps the main scan below from finding it; it must be scanned as its own root to be
 * discovered at all, flagged [system][SkillSourceRecord.system].
 */
class CodexSkillProvider(
    userHome: Path = Path.of(System.getProperty("user.home")),
) : DirectorySkillProvider(
    agentId = "codex",
    userHome = userHome,
    relativeSkillDirectory = Path.of(".codex", "skills"),
    shared = false,
    globalSkillDirectory = EnvHomeDirectorySupport.resolveGuarded("CODEX_HOME", userHome, ".codex").resolve("skills"),
) {
    private val scanner = SkillDirectoryScanner()

    override fun discoverGlobal(): List<SkillSourceRecord> {
        val root = resolveGlobalDirectory()
        val own = scanner.discover(
            root = root,
            agentId = agentId,
            scope = SkillScope.GLOBAL,
            shared = false,
            projectName = null,
        )
        val system = scanner.discover(
            root = root.resolve(SYSTEM_DIRECTORY),
            agentId = agentId,
            scope = SkillScope.GLOBAL,
            shared = false,
            projectName = null,
            system = true,
        )
        return (own + system).distinctBy { it.path }
    }

    private companion object {
        const val SYSTEM_DIRECTORY = ".system"
    }
}
