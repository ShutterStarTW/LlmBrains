package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.OsDetector
import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * Kilo Code CLI skills. Kilo scans `skills/` (and the singular `skill/`) in every config directory — `~/.config/kilo`, `~/.kilo`,
 * `~/.kilocode` (legacy), `$KILO_CONFIG_DIR` and the VS Code extension's global storage — plus the
 * Claude Code compatibility root (on unless `KILO_DISABLE_CLAUDE_CODE[_SKILLS]` is set). The shared
 * `.agents/skills` root is the `SharedSkillProvider`'s. Project skills live in `.kilo/` and `.kilocode/`
 * (found while walking up from the working directory) and the project `.claude/`.
 */
class KiloSkillProvider(
    private val userHome: Path = AgentRuntime.userHome(),
) : SkillProvider {
    override val agentId: String = AGENT_ID
    private val scanner = SkillDirectoryScanner()

    private val globalRoots: List<Path> = listOfNotNull(
        EnvHomeDirectorySupport.resolveXdgGuarded("XDG_CONFIG_HOME", userHome, ".config", "kilo"),
        userHome.resolve(KILO_DIRECTORY),
        userHome.resolve(LEGACY_KILO_DIRECTORY),
        EnvHomeDirectorySupport.configuredDirectoryGuarded("KILO_CONFIG_DIR", userHome),
        vscodeGlobalStorage(),
    ).flatMap { directory -> SKILL_DIRECTORIES.map(directory::resolve) } +
        listOf(userHome.resolve(CLAUDE_DIRECTORY).resolve(SKILLS_DIRECTORY))

    override fun discoverGlobal(): List<SkillSourceRecord> {
        val budget = scanner.newBudget()
        return globalRoots.flatMap { root ->
            scanner.discover(
                root = root,
                agentId = agentId,
                scope = SkillScope.GLOBAL,
                shared = false,
                projectName = null,
                requireValidMetadata = true,
                budget = budget,
            )
        }.distinctBy { it.path }
    }

    override fun discoverProject(project: DiscoveredProject): List<SkillSourceRecord> {
        val projectRoot = ProjectPathResolver.resolveExistingRoot(project) ?: return emptyList()
        val budget = scanner.newBudget()
        val nested = listOf(KILO_DIRECTORY, LEGACY_KILO_DIRECTORY, CLAUDE_DIRECTORY).flatMap { ownerDirectory ->
            scanner.discoverNestedProjectSkills(
                projectRoot = projectRoot,
                ownerDirectoryName = ownerDirectory,
                agentId = agentId,
                shared = false,
                projectName = project.name,
                requireValidMetadata = true,
                budget = budget,
            )
        }
        // The singular `skill/` folder is accepted next to `skills/` in the Kilo directories (not in `.claude`).
        val singular = listOf(KILO_DIRECTORY, LEGACY_KILO_DIRECTORY).flatMap { ownerDirectory ->
            scanner.discover(
                root = projectRoot.resolve(ownerDirectory).resolve(SINGULAR_DIRECTORY),
                agentId = agentId,
                scope = SkillScope.PROJECT,
                shared = false,
                projectName = project.name,
                requireValidMetadata = true,
                budget = budget,
            )
        }
        return (nested + singular).distinctBy { it.path }
    }

    /** `…/Code/User/globalStorage/kilocode.kilo-code`: skills installed through the VS Code extension's marketplace. */
    private fun vscodeGlobalStorage(): Path? {
        val storage = Path.of("Code", "User", "globalStorage", "kilocode.kilo-code")
        return when {
            AgentRuntime.isWindowsRuntime() ->
                (EnvHomeDirectorySupport.configuredDirectoryGuarded("APPDATA", userHome) ?: userHome.resolve("AppData").resolve("Roaming"))
                    .resolve(storage)
            OsDetector.isMac() && !AgentRuntime.isWsl() -> userHome.resolve("Library").resolve("Application Support").resolve(storage)
            else -> userHome.resolve(".config").resolve(storage)
        }
    }

    private companion object {
        const val AGENT_ID = "kilo"
        const val KILO_DIRECTORY = ".kilo"
        const val LEGACY_KILO_DIRECTORY = ".kilocode"
        const val CLAUDE_DIRECTORY = ".claude"
        const val SKILLS_DIRECTORY = "skills"
        const val SINGULAR_DIRECTORY = "skill"
        val SKILL_DIRECTORIES = listOf(SKILLS_DIRECTORY, SINGULAR_DIRECTORY)
    }
}
