package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import java.nio.file.Path

/**
 * Claude Code caches skills synced from the user's Claude.ai account under `skills/synced/<bucket>/`
 * (a mix of Anthropic's own built-in skills - docx, pdf, computer-use, etc. - and the user's own
 * skills mirrored back down) - not something the user created locally, so it's scanned separately
 * and flagged [system][com.shutterstar.agenthub.environment.skills.discovery.SkillSourceRecord.system],
 * distinct from the plain top-level entries in `skills/` (which the main scan below excludes it from,
 * to avoid discovering the same skill twice under two different flags).
 */
class ClaudeSkillProvider(
    userHome: Path = Path.of(System.getProperty("user.home")),
) : DirectorySkillProvider(
    agentId = "claude",
    userHome = userHome,
    relativeSkillDirectory = Path.of(".claude", "skills"),
    shared = false,
    globalSkillDirectory = EnvHomeDirectorySupport.resolveGuarded("CLAUDE_CONFIG_DIR", userHome, ".claude").resolve("skills"),
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
            excludeDirectoryNames = setOf(SYNCED_DIRECTORY),
        )
        val synced = scanner.discover(
            root = root.resolve(SYNCED_DIRECTORY),
            agentId = agentId,
            scope = SkillScope.GLOBAL,
            shared = false,
            projectName = null,
            system = true,
        )
        return (own + synced).distinctBy { it.path }
    }

    private companion object {
        const val SYNCED_DIRECTORY = "synced"
    }
}
