package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class OpenCodeInstructionProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : InstructionProvider {
    override val agentId: String = AGENT_ID
    private val globalFile = homeDirectory.resolve(".config").resolve("opencode").resolve(AGENTS_FILE)
    private val globalClaudeFile = homeDirectory.resolve(".claude").resolve(CLAUDE_FILE)

    override fun discoverGlobal(): List<InstructionSource> = listOfNotNull(
        InstructionFileSupport.source(
            path = if (isNonEmptyFile(globalFile)) globalFile else globalClaudeFile,
            scope = InstructionScope.GLOBAL,
            agentId = agentId,
            type = if (isNonEmptyFile(globalFile)) InstructionType.AGENTS_MD else InstructionType.CLAUDE_MD,
        ),
    )

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val projectRoot = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        return InstructionFileSupport.scan(projectRoot) { _, file ->
            val fileName = file.fileName.toString()
            fileName.equals(AGENTS_FILE, ignoreCase = true) ||
                fileName.equals(CLAUDE_FILE, ignoreCase = true)
        }.filterNot { file ->
            file.fileName.toString().equals(CLAUDE_FILE, ignoreCase = true) &&
                Files.isRegularFile(file.resolveSibling(AGENTS_FILE), LinkOption.NOFOLLOW_LINKS)
        }.mapNotNull { file ->
            val type = if (file.fileName.toString().equals(AGENTS_FILE, ignoreCase = true)) {
                InstructionType.AGENTS_MD
            } else {
                InstructionType.CLAUDE_MD
            }
            InstructionFileSupport.source(file, InstructionScope.PROJECT, agentId, type, project.name)
        }
    }

    private fun isNonEmptyFile(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { Files.size(path) > 0L }.getOrDefault(false)

    private companion object {
        const val AGENT_ID = "opencode"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
    }
}
