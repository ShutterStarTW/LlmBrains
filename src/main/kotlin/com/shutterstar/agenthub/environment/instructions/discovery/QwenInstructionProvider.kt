package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path

class QwenInstructionProvider(
    private val homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : InstructionProvider {
    override val agentId: String = "qwen"

    override fun discoverGlobal(): List<InstructionSource> = listOfNotNull(
        InstructionFileSupport.source(
            homeDirectory.resolve(".qwen/QWEN.md"),
            InstructionScope.GLOBAL,
            agentId,
            InstructionType.AGENT_SPECIFIC,
        ),
    )

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val root = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        return InstructionFileSupport.scan(root) { scanRoot, file -> isProjectInstruction(scanRoot, file) }
            .mapNotNull { path ->
                val type = if (path.fileName.toString() == "AGENTS.md") {
                    InstructionType.AGENTS_MD
                } else {
                    InstructionType.AGENT_SPECIFIC
                }
                InstructionFileSupport.source(path, InstructionScope.PROJECT, agentId, type, project.name)
            }
    }

    private fun isProjectInstruction(root: Path, file: Path): Boolean {
        val normalized = file.toAbsolutePath().normalize()
        return file.fileName.toString() == "AGENTS.md" ||
            normalized == root.resolve("QWEN.md").toAbsolutePath().normalize() ||
            normalized == root.resolve(".qwen/QWEN.local.md").toAbsolutePath().normalize()
    }
}
