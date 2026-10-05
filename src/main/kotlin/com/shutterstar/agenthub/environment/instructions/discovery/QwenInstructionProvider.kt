package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path
import kotlin.io.path.extension

class QwenInstructionProvider(
    private val homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : InstructionProvider {
    override val agentId: String = "qwen"
    private val qwenDirectory = EnvHomeDirectorySupport.resolveGuarded("QWEN_HOME", homeDirectory, ".qwen")

    override fun discoverGlobal(): List<InstructionSource> = listOfNotNull(
        InstructionFileSupport.source(
            qwenDirectory.resolve("QWEN.md"),
            InstructionScope.GLOBAL,
            agentId,
            InstructionType.AGENT_SPECIFIC,
        ),
    ) + rules(qwenDirectory.resolve(RULES_DIRECTORY), InstructionScope.GLOBAL, null)

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val root = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        val rootDirectory = root.toAbsolutePath().normalize()
        val files = InstructionFileSupport.scan(root) { scanRoot, file -> isProjectInstruction(scanRoot, file) }
            .mapNotNull { path ->
                val isAgentsFile = path.fileName.toString() == "AGENTS.md"
                val type = if (isAgentsFile) InstructionType.AGENTS_MD else InstructionType.AGENT_SPECIFIC
                // Qwen Code searches upward from the working directory to the project root, so a QWEN.md
                // below the root is applied only when Qwen is started in that directory.
                val note = NESTED_NOTE.takeIf {
                    path.fileName.toString() == "QWEN.md" && path.toAbsolutePath().normalize().parent != rootDirectory
                }
                InstructionFileSupport.source(path, InstructionScope.PROJECT, agentId, type, project.name, note)
            }
        return files + rules(root.resolve(".qwen").resolve(RULES_DIRECTORY), InstructionScope.PROJECT, project.name)
    }

    /** Path-based context rules: every Markdown file under `<QWEN_HOME>/rules/` and `<project>/.qwen/rules/`, recursively. */
    private fun rules(directory: Path, scope: InstructionScope, projectName: String?): List<InstructionSource> =
        InstructionFileSupport.scan(directory) { _, file -> file.extension.equals("md", ignoreCase = true) }
            .mapNotNull { path -> InstructionFileSupport.source(path, scope, agentId, InstructionType.AGENT_SPECIFIC, projectName) }

    private fun isProjectInstruction(root: Path, file: Path): Boolean {
        val normalized = file.toAbsolutePath().normalize()
        return file.fileName.toString() == "AGENTS.md" ||
            file.fileName.toString() == "QWEN.md" ||
            normalized == root.resolve(".qwen/QWEN.local.md").toAbsolutePath().normalize()
    }

    private companion object {
        const val RULES_DIRECTORY = "rules"
        const val NESTED_NOTE =
            "Loaded only when Qwen Code is started in this directory (it searches from the working directory up to the project root)."
    }
}
