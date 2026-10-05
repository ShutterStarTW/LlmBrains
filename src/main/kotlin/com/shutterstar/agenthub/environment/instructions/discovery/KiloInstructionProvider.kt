package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Kilo Code CLI instructions (source: `session/instruction.ts`): the global `AGENTS.md` of
 * `$KILO_CONFIG_DIR` and `~/.config/kilo`, falling back to the global Claude Code file; per directory
 * `AGENTS.md`, with `CLAUDE.md` only as a fallback when no `AGENTS.md` is there. A `CLAUDE.md` that
 * is shadowed by an `AGENTS.md` is still listed, with an info line saying why it is not applied.
 * (The deprecated `CONTEXT.md` and the `KILO_DISABLE_CLAUDE_CODE[_PROMPT]` switches are not modelled.)
 */
class KiloInstructionProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : InstructionProvider {
    override val agentId: String = AGENT_ID
    private val globalFiles = listOfNotNull(
        EnvHomeDirectorySupport.configuredDirectoryGuarded("KILO_CONFIG_DIR", homeDirectory)?.resolve(AGENTS_FILE),
        EnvHomeDirectorySupport.resolveXdgGuarded("XDG_CONFIG_HOME", homeDirectory, ".config", "kilo").resolve(AGENTS_FILE),
    )
    private val globalClaudeFile = homeDirectory.resolve(".claude").resolve(CLAUDE_FILE)

    override fun discoverGlobal(): List<InstructionSource> {
        val agents = globalFiles.firstOrNull(::isNonEmptyFile)
        val file = agents ?: globalClaudeFile
        val type = if (agents != null) InstructionType.AGENTS_MD else InstructionType.CLAUDE_MD
        return listOfNotNull(InstructionFileSupport.source(file, InstructionScope.GLOBAL, agentId, type))
    }

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val projectRoot = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        return InstructionFileSupport.scan(projectRoot) { _, file ->
            val fileName = file.fileName.toString()
            fileName == AGENTS_FILE || fileName == CLAUDE_FILE
        }.mapNotNull { file ->
            val isAgents = file.fileName.toString() == AGENTS_FILE
            val shadowed = !isAgents && isNonEmptyFile(file.resolveSibling(AGENTS_FILE))
            InstructionFileSupport.source(
                path = file,
                scope = InstructionScope.PROJECT,
                agentId = agentId,
                type = if (isAgents) InstructionType.AGENTS_MD else InstructionType.CLAUDE_MD,
                projectName = project.name,
                note = SHADOWED_NOTE.takeIf { shadowed },
            )
        }
    }

    private fun isNonEmptyFile(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { Files.size(path) > 0L }.getOrDefault(false)

    private companion object {
        const val AGENT_ID = "kilo"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val SHADOWED_NOTE = "Not used by Kilo: it reads CLAUDE.md only when no AGENTS.md exists in the same directory."
    }
}
