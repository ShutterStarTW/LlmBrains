package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path
import kotlin.io.path.extension

class GrokInstructionProvider(
    private val homeDirectory: Path = Path.of(System.getProperty("user.home")),
    private val grokDirectory: Path = defaultGrokDirectory(homeDirectory),
) : InstructionProvider {
    override val agentId: String = AGENT_ID

    override fun discoverGlobal(): List<InstructionSource> {
        val namedFiles = GLOBAL_INSTRUCTION_FILES.mapNotNull { (fileName, type) ->
            InstructionFileSupport.source(
                grokDirectory.resolve(fileName),
                InstructionScope.GLOBAL,
                agentId,
                type,
            )
        }
        val rules = InstructionFileSupport.scan(grokDirectory.resolve(RULES_DIRECTORY)) { _, file ->
            file.extension.equals(MARKDOWN_EXTENSION, ignoreCase = true)
        }.mapNotNull { path ->
            InstructionFileSupport.source(
                path,
                InstructionScope.GLOBAL,
                agentId,
                InstructionType.AGENT_SPECIFIC,
            )
        }
        return (namedFiles + rules).distinctBy(InstructionSource::path)
    }

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val projectRoot = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        return InstructionFileSupport.scan(projectRoot) { _, file -> isProjectInstruction(projectRoot, file) }
            .mapNotNull { path ->
                InstructionFileSupport.source(
                    path,
                    InstructionScope.PROJECT,
                    agentId,
                    instructionType(projectRoot, path),
                    project.name,
                )
            }
    }

    private fun isProjectInstruction(root: Path, file: Path): Boolean {
        val fileName = file.fileName.toString()
        if (isAgentsFile(fileName) || isClaudeFile(fileName)) return true
        val extension = file.extension.lowercase()
        if (
            extension == MARKDOWN_EXTENSION &&
            (
                InstructionFileSupport.isWithinDirectory(root, file, GROK_DIRECTORY, RULES_DIRECTORY) ||
                    InstructionFileSupport.isWithinDirectory(root, file, CLAUDE_DIRECTORY, RULES_DIRECTORY)
                )
        ) {
            return true
        }
        return (extension == MARKDOWN_EXTENSION || extension == MDC_EXTENSION) &&
            InstructionFileSupport.isWithinDirectory(root, file, CURSOR_DIRECTORY, RULES_DIRECTORY)
    }

    private fun instructionType(root: Path, path: Path): InstructionType {
        val fileName = path.fileName.toString()
        return when {
            isAgentsFile(fileName) -> InstructionType.AGENTS_MD
            isClaudeFile(fileName) -> InstructionType.CLAUDE_MD
            InstructionFileSupport.isWithinDirectory(root, path, CURSOR_DIRECTORY, RULES_DIRECTORY) ->
                InstructionType.CURSOR_RULE
            else -> InstructionType.AGENT_SPECIFIC
        }
    }

    private companion object {
        const val AGENT_ID = "grok"
        const val GROK_DIRECTORY = ".grok"
        const val CLAUDE_DIRECTORY = ".claude"
        const val CURSOR_DIRECTORY = ".cursor"
        const val RULES_DIRECTORY = "rules"
        const val MARKDOWN_EXTENSION = "md"
        const val MDC_EXTENSION = "mdc"
        val GLOBAL_INSTRUCTION_FILES = listOf(
            "AGENTS.md" to InstructionType.AGENTS_MD,
            "AGENT.md" to InstructionType.AGENTS_MD,
            "CLAUDE.md" to InstructionType.CLAUDE_MD,
            "CLAUDE.local.md" to InstructionType.CLAUDE_MD,
        )

        fun isAgentsFile(fileName: String): Boolean =
            fileName.equals("AGENTS.md", ignoreCase = true) ||
                fileName.equals("AGENT.md", ignoreCase = true)

        fun isClaudeFile(fileName: String): Boolean =
            fileName.equals("CLAUDE.md", ignoreCase = true) ||
                fileName.equals("CLAUDE.local.md", ignoreCase = true)

        fun defaultGrokDirectory(homeDirectory: Path): Path =
            EnvHomeDirectorySupport.resolveGuarded("GROK_HOME", homeDirectory, GROK_DIRECTORY)
    }
}
