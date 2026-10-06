package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.discovery.MimoHomeSupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * MiMo Code CLI instructions (source `packages/cli/src/session/instruction.ts`): the first existing global file of
 * `$MIMOCODE_CONFIG_DIR/AGENTS.md`, `<config dir>/AGENTS.md` and the Claude Code `~/.claude/CLAUDE.md`; per project
 * `AGENTS.md` (searched upwards), with `CLAUDE.md` only as a fallback when no `AGENTS.md` exists or that one is shorter
 * than 500 characters. A `CLAUDE.md` that therefore is not applied is still listed, with an info line saying why. (The
 * deprecated `CONTEXT.md` and the `MIMOCODE_DISABLE_CLAUDE_CODE[_PROMPT]` switches are not modelled.)
 */
class MimoInstructionProvider(
    homeDirectory: Path = AgentRuntime.userHome(),
) : InstructionProvider {
    override val agentId: String = AGENT_ID
    private val globalFiles = listOfNotNull(
        EnvHomeDirectorySupport.configuredDirectoryGuarded("MIMOCODE_CONFIG_DIR", homeDirectory)?.resolve(AGENTS_FILE),
        MimoHomeSupport.configDirectory(homeDirectory).resolve(AGENTS_FILE),
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
            val shadowed = !isAgents && isSubstantial(file.resolveSibling(AGENTS_FILE))
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

    /** An `AGENTS.md` with at least [FALLBACK_MAX_CHARACTERS] characters (trimmed): enough that MiMo ignores `CLAUDE.md`. */
    private fun isSubstantial(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { Files.readString(path).trim().length >= FALLBACK_MAX_CHARACTERS }.getOrDefault(false)

    private companion object {
        const val AGENT_ID = "mimo"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val FALLBACK_MAX_CHARACTERS = 500
        const val SHADOWED_NOTE =
            "Not used by MiMo Code: it reads CLAUDE.md only when no AGENTS.md exists in the same directory or that AGENTS.md is shorter than 500 characters."
    }
}
