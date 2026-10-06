package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.OmpHomeSupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * Oh My Pi instructions (docs: `context-files.md`; source: `discovery/agents.ts`): the native `AGENTS.md` and sticky
 * `RULES.md` of the agent directory (`~/.omp/agent`); per project the native `.omp/AGENTS.md` / `.omp/RULES.md` and the
 * standalone `AGENTS.md` files (OMP ignores `AGENTS.md` inside other dot-directories — those belong to config-directory
 * providers). The vendor-neutral `.agent/AGENTS.md` and `.agents/AGENTS.md` are read at the user level and in the
 * project, like OMP's "Agent Dirs" provider does. The cross-tool
 * context providers (`.claude/CLAUDE.md`, `.gemini/GEMINI.md`, `.github/copilot-instructions.md`, …) and the
 * "nearest non-empty `.omp`" walk rule are not modelled.
 */
class OmpInstructionProvider(
    private val homeDirectory: Path = AgentRuntime.userHome(),
) : InstructionProvider {
    override val agentId: String = AGENT_ID
    private val agentDirectory = OmpHomeSupport.agentDirectory(homeDirectory)

    override fun discoverGlobal(): List<InstructionSource> = listOfNotNull(
        InstructionFileSupport.source(agentDirectory.resolve(AGENTS_FILE), InstructionScope.GLOBAL, agentId, InstructionType.AGENTS_MD),
        InstructionFileSupport.source(agentDirectory.resolve(RULES_FILE), InstructionScope.GLOBAL, agentId, InstructionType.AGENT_SPECIFIC),
    ) + NEUTRAL_DIRECTORIES.mapNotNull { directory ->
        InstructionFileSupport.source(
            homeDirectory.resolve(directory).resolve(AGENTS_FILE),
            InstructionScope.GLOBAL,
            agentId,
            InstructionType.AGENTS_MD,
        )
    }

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val projectRoot = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        return InstructionFileSupport.scan(projectRoot) { _, file ->
            val name = file.fileName.toString()
            val parent = file.parent?.fileName?.toString().orEmpty()
            (name == AGENTS_FILE && (!parent.startsWith(".") || parent in NEUTRAL_DIRECTORIES)) ||
                (parent == NATIVE_DIRECTORY && (name == AGENTS_FILE || name == RULES_FILE))
        }.mapNotNull { file ->
            val type = if (file.fileName.toString() == RULES_FILE) InstructionType.AGENT_SPECIFIC else InstructionType.AGENTS_MD
            InstructionFileSupport.source(file, InstructionScope.PROJECT, agentId, type, project.name)
        }
    }

    private companion object {
        const val AGENT_ID = "omp"
        const val AGENTS_FILE = "AGENTS.md"
        const val RULES_FILE = "RULES.md"
        const val NATIVE_DIRECTORY = ".omp"
        /** `.agent` and `.agents`: the vendor-neutral directories OMP's "Agent Dirs" provider reads. */
        val NEUTRAL_DIRECTORIES = listOf(".agent", ".agents")
    }
}
