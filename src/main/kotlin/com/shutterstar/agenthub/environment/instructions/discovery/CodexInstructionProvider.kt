package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path

class CodexInstructionProvider(
    private val codexDirectory: Path = defaultCodexDirectory(),
) : InstructionProvider {
    override val agentId: String = AGENT_ID

    override fun discoverGlobal(): List<InstructionSource> =
        preferredSource(codexDirectory, InstructionScope.GLOBAL)?.let(::listOf).orEmpty()

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val projectRoot = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        val candidates = InstructionFileSupport.scan(projectRoot) { _, file ->
            file.fileName.toString() == OVERRIDE_FILE || file.fileName.toString() == INSTRUCTION_FILE
        }
        return candidates
            .groupBy(Path::getParent)
            .values
            .mapNotNull { directoryFiles ->
                val override = directoryFiles.firstOrNull { it.fileName.toString() == OVERRIDE_FILE }
                val standard = directoryFiles.firstOrNull { it.fileName.toString() == INSTRUCTION_FILE }
                (override ?: standard)?.let { path ->
                    InstructionFileSupport.source(
                        path,
                        InstructionScope.PROJECT,
                        agentId,
                        InstructionType.AGENTS_MD,
                        project.name,
                    )
                }
            }
            .sortedBy { it.path.lowercase() }
    }

    private fun preferredSource(directory: Path, scope: InstructionScope): InstructionSource? =
        InstructionFileSupport.source(
            directory.resolve(OVERRIDE_FILE),
            scope,
            agentId,
            InstructionType.AGENTS_MD,
        ) ?: InstructionFileSupport.source(
            directory.resolve(INSTRUCTION_FILE),
            scope,
            agentId,
            InstructionType.AGENTS_MD,
        )

    private companion object {
        const val AGENT_ID = "codex"
        const val OVERRIDE_FILE = "AGENTS.override.md"
        const val INSTRUCTION_FILE = "AGENTS.md"
        const val CODEX_DIRECTORY = ".codex"

        fun defaultCodexDirectory(): Path = EnvHomeDirectorySupport.resolve("CODEX_HOME", CODEX_DIRECTORY)
    }
}
