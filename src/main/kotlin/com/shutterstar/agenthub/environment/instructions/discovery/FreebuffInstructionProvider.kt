package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * Freebuff instructions (Codebuff source `common/src/constants/knowledge.ts`, `sdk/src/run-state.ts`): the knowledge
 * files `AGENTS.md`, `CLAUDE.md` and `*.knowledge.md` of the project; per directory only the highest-priority one of
 * `AGENTS.md` / `CLAUDE.md` is used, so a `CLAUDE.md` next to an `AGENTS.md` is listed with an info line. The user-level
 * file is the dot-prefixed `~/.AGENTS.md`, else `~/.CLAUDE.md`.
 */
class FreebuffInstructionProvider(
    private val homeDirectory: Path = AgentRuntime.userHome(),
) : InstructionProvider {
    override val agentId: String = AGENT_ID

    override fun discoverGlobal(): List<InstructionSource> {
        val file = listOf(".$AGENTS_FILE", ".$CLAUDE_FILE").map(homeDirectory::resolve).firstOrNull(::isNonEmptyFile) ?: return emptyList()
        val type = if (file.fileName.toString() == ".$AGENTS_FILE") InstructionType.AGENTS_MD else InstructionType.CLAUDE_MD
        return listOfNotNull(InstructionFileSupport.source(file, InstructionScope.GLOBAL, agentId, type))
    }

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val projectRoot = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        return InstructionFileSupport.scan(projectRoot) { _, file ->
            val name = file.fileName.toString().lowercase()
            name == AGENTS_FILE.lowercase() || name == CLAUDE_FILE.lowercase() || name.endsWith(KNOWLEDGE_SUFFIX)
        }.mapNotNull { file ->
            val name = file.fileName.toString()
            val type = when {
                name.equals(AGENTS_FILE, ignoreCase = true) -> InstructionType.AGENTS_MD
                name.equals(CLAUDE_FILE, ignoreCase = true) -> InstructionType.CLAUDE_MD
                else -> InstructionType.AGENT_SPECIFIC
            }
            val shadowed = type == InstructionType.CLAUDE_MD && isNonEmptyFile(file.resolveSibling(AGENTS_FILE))
            InstructionFileSupport.source(file, InstructionScope.PROJECT, agentId, type, project.name, SHADOWED_NOTE.takeIf { shadowed })
        }
    }

    private fun isNonEmptyFile(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { Files.size(path) > 0L }.getOrDefault(false)

    private companion object {
        const val AGENT_ID = "freebuff"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val KNOWLEDGE_SUFFIX = ".knowledge.md"
        const val SHADOWED_NOTE = "Not used by Freebuff: it uses only one of AGENTS.md and CLAUDE.md per directory, and AGENTS.md has priority."
    }
}
