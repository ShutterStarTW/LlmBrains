package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path
import com.shutterstar.agenthub.AgentRuntime

/**
 * Kimi Code CLI instructions (docs: `customization/agents.md`): the global `AGENTS.md` and `SYSTEM.md` (which replaces the
 * default main agent's system prompt) of the data root (`~/.kimi-code`, or `KIMI_CODE_HOME`), the generic cross-tool
 * `~/.agents/AGENTS.md` in the real home, and per project `AGENTS.md` files and `.kimi-code/AGENTS.md`. Agent definition
 * files (`agents/`) are not instructions and not modelled.
 */
class KimiInstructionProvider(
    homeDirectory: Path = AgentRuntime.userHome(),
) : InstructionProvider {
    override val agentId: String = AGENT_ID
    private val dataDirectory = EnvHomeDirectorySupport.resolveGuarded("KIMI_CODE_HOME", homeDirectory, DATA_DIRECTORY)
    private val genericGlobalFile = homeDirectory.resolve(".agents").resolve(AGENTS_FILE)

    override fun discoverGlobal(): List<InstructionSource> = listOfNotNull(
        InstructionFileSupport.source(dataDirectory.resolve(AGENTS_FILE), InstructionScope.GLOBAL, agentId, InstructionType.AGENTS_MD),
        InstructionFileSupport.source(genericGlobalFile, InstructionScope.GLOBAL, agentId, InstructionType.AGENTS_MD),
        InstructionFileSupport.source(dataDirectory.resolve(SYSTEM_FILE), InstructionScope.GLOBAL, agentId, InstructionType.AGENT_SPECIFIC),
    )

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val projectRoot = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        return InstructionFileSupport.scan(projectRoot) { _, file ->
            val name = file.fileName.toString()
            val parent = file.parent?.fileName?.toString().orEmpty()
            name == AGENTS_FILE && (!parent.startsWith(".") || parent == DATA_DIRECTORY)
        }.mapNotNull { file ->
            InstructionFileSupport.source(file, InstructionScope.PROJECT, agentId, InstructionType.AGENTS_MD, project.name)
        }
    }

    private companion object {
        const val AGENT_ID = "kimi"
        const val DATA_DIRECTORY = ".kimi-code"
        const val AGENTS_FILE = "AGENTS.md"
        const val SYSTEM_FILE = "SYSTEM.md"
    }
}
