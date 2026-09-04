package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path
import kotlin.io.path.extension

class ClaudeInstructionProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : InstructionProvider {
    override val agentId: String = AGENT_ID

    private val claudeDirectory = homeDirectory.resolve(CLAUDE_DIRECTORY)

    override fun discoverGlobal(): List<InstructionSource> {
        val mainInstruction = InstructionFileSupport.source(
            claudeDirectory.resolve(CLAUDE_FILE),
            InstructionScope.GLOBAL,
            agentId,
            InstructionType.CLAUDE_MD,
        )
        val rules = InstructionFileSupport.scan(claudeDirectory.resolve(RULES_DIRECTORY)) { _, file ->
            file.extension.equals(MARKDOWN_EXTENSION, ignoreCase = true)
        }.mapNotNull { path ->
            InstructionFileSupport.source(path, InstructionScope.GLOBAL, agentId, InstructionType.AGENT_SPECIFIC)
        }
        return listOfNotNull(mainInstruction) + rules
    }

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val projectRoot = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        return InstructionFileSupport.scan(projectRoot) { root, file ->
            val fileName = file.fileName.toString()
            fileName == CLAUDE_FILE ||
                fileName == CLAUDE_LOCAL_FILE ||
                (
                    file.extension.equals(MARKDOWN_EXTENSION, ignoreCase = true) &&
                        InstructionFileSupport.isWithinDirectory(root, file, CLAUDE_DIRECTORY, RULES_DIRECTORY)
                    )
        }.mapNotNull { path ->
            val type = if (path.fileName.toString() == CLAUDE_FILE || path.fileName.toString() == CLAUDE_LOCAL_FILE) {
                InstructionType.CLAUDE_MD
            } else {
                InstructionType.AGENT_SPECIFIC
            }
            InstructionFileSupport.source(path, InstructionScope.PROJECT, agentId, type, project.name)
        }
    }

    private companion object {
        const val AGENT_ID = "claude"
        const val CLAUDE_DIRECTORY = ".claude"
        const val RULES_DIRECTORY = "rules"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val CLAUDE_LOCAL_FILE = "CLAUDE.local.md"
        const val MARKDOWN_EXTENSION = "md"
    }
}
