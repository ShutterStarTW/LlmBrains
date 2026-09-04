package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path

class CopilotInstructionProvider(
    private val copilotDirectory: Path = defaultCopilotDirectory(),
) : InstructionProvider {
    override val agentId: String = AGENT_ID

    override fun discoverGlobal(): List<InstructionSource> {
        val mainInstruction = InstructionFileSupport.source(
            copilotDirectory.resolve(COPILOT_INSTRUCTIONS_FILE),
            InstructionScope.GLOBAL,
            agentId,
            InstructionType.COPILOT_INSTRUCTION,
        )
        val modularInstructions = InstructionFileSupport.scan(copilotDirectory.resolve(INSTRUCTIONS_DIRECTORY)) { _, file ->
            file.fileName.toString().endsWith(MODULAR_INSTRUCTIONS_SUFFIX, ignoreCase = true)
        }.mapNotNull { path ->
            InstructionFileSupport.source(
                path,
                InstructionScope.GLOBAL,
                agentId,
                InstructionType.COPILOT_INSTRUCTION,
            )
        }
        return listOfNotNull(mainInstruction) + modularInstructions
    }

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val projectRoot = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        return InstructionFileSupport.scan(projectRoot) { root, file -> isProjectInstruction(root, file) }
            .mapNotNull { path ->
                InstructionFileSupport.source(
                    path,
                    InstructionScope.PROJECT,
                    agentId,
                    instructionType(path),
                    project.name,
                )
            }
    }

    private fun isProjectInstruction(root: Path, file: Path): Boolean {
        val fileName = file.fileName.toString()
        return fileName == AGENTS_FILE ||
            fileName == CLAUDE_FILE ||
            fileName == GEMINI_FILE ||
            (fileName == COPILOT_INSTRUCTIONS_FILE && file.parent?.fileName?.toString() == GITHUB_DIRECTORY) ||
            (
                fileName.endsWith(MODULAR_INSTRUCTIONS_SUFFIX, ignoreCase = true) &&
                    InstructionFileSupport.isWithinDirectory(root, file, GITHUB_DIRECTORY, INSTRUCTIONS_DIRECTORY)
                )
    }

    private fun instructionType(path: Path): InstructionType = when (path.fileName.toString()) {
        AGENTS_FILE -> InstructionType.AGENTS_MD
        CLAUDE_FILE -> InstructionType.CLAUDE_MD
        GEMINI_FILE -> InstructionType.GEMINI_MD
        else -> InstructionType.COPILOT_INSTRUCTION
    }

    private companion object {
        const val AGENT_ID = "copilot"
        const val COPILOT_DIRECTORY = ".copilot"
        const val GITHUB_DIRECTORY = ".github"
        const val INSTRUCTIONS_DIRECTORY = "instructions"
        const val COPILOT_INSTRUCTIONS_FILE = "copilot-instructions.md"
        const val MODULAR_INSTRUCTIONS_SUFFIX = ".instructions.md"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val GEMINI_FILE = "GEMINI.md"

        fun defaultCopilotDirectory(): Path = EnvHomeDirectorySupport.resolve("COPILOT_HOME", COPILOT_DIRECTORY)
    }
}
