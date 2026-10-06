package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * Mistral Vibe instructions (`vibe/core/config/harness_files/_harness_manager.py`): the global `AGENTS.md` of the vibe
 * home (`~/.vibe`, or `VIBE_HOME`) and every `AGENTS.md` from the project root up to its trust root, plus the ones in
 * sub-directories that Vibe injects when it reads a file there. Custom system prompts (Markdown files in `.vibe/prompts/`) are not
 * instruction files and not modelled.
 */
class VibeInstructionProvider(
    homeDirectory: Path = AgentRuntime.userHome(),
) : InstructionProvider {
    override val agentId: String = AGENT_ID
    private val vibeHome = EnvHomeDirectorySupport.resolveGuarded("VIBE_HOME", homeDirectory, VIBE_DIRECTORY)

    override fun discoverGlobal(): List<InstructionSource> = listOfNotNull(
        InstructionFileSupport.source(vibeHome.resolve(AGENTS_FILE), InstructionScope.GLOBAL, agentId, InstructionType.AGENTS_MD),
    )

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val projectRoot = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        return InstructionFileSupport.scan(projectRoot) { _, file ->
            file.fileName.toString() == AGENTS_FILE && !file.parent?.fileName?.toString().orEmpty().startsWith(".")
        }.mapNotNull { file ->
            InstructionFileSupport.source(file, InstructionScope.PROJECT, agentId, InstructionType.AGENTS_MD, project.name)
        }
    }

    private companion object {
        const val AGENT_ID = "vibe"
        const val VIBE_DIRECTORY = ".vibe"
        const val AGENTS_FILE = "AGENTS.md"
    }
}
