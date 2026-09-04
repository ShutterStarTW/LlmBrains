package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path
import kotlin.io.path.extension

class ClineInstructionProvider(
    private val homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : InstructionProvider {
    override val agentId: String = "cline"

    override fun discoverGlobal(): List<InstructionSource> = listOf(
        homeDirectory.resolve(".cline/rules"),
        homeDirectory.resolve("Documents/Cline/Rules"),
        homeDirectory.resolve("Cline/Rules"),
    ).flatMap { root -> discoverRules(root, InstructionScope.GLOBAL) }

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val root = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        return InstructionFileSupport.scan(root) { scanRoot, file -> isProjectRule(scanRoot, file) }
            .mapNotNull { path ->
                val type = if (path.fileName.toString() == "AGENTS.md") {
                    InstructionType.AGENTS_MD
                } else {
                    InstructionType.AGENT_SPECIFIC
                }
                InstructionFileSupport.source(path, InstructionScope.PROJECT, agentId, type, project.name)
            }
    }

    private fun discoverRules(root: Path, scope: InstructionScope): List<InstructionSource> =
        InstructionFileSupport.scan(root) { _, file -> file.extension.lowercase() in RULE_EXTENSIONS }
            .mapNotNull { path -> InstructionFileSupport.source(path, scope, agentId, InstructionType.AGENT_SPECIFIC) }

    private fun isProjectRule(root: Path, file: Path): Boolean {
        val name = file.fileName.toString()
        if (name == "AGENTS.md" || name == ".cursorrules" || name == ".windsurfrules") return true
        if (file.extension.lowercase() !in RULE_EXTENSIONS) return false
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedFile = file.toAbsolutePath().normalize()
        if (!normalizedFile.startsWith(normalizedRoot)) return false
        val relativeSegments = normalizedRoot.relativize(normalizedFile).iterator().asSequence()
            .map { it.toString().lowercase() }.toSet()
        return ".clinerules" in relativeSegments ||
            InstructionFileSupport.isWithinDirectory(root, file, ".cline", "rules")
    }

    private companion object {
        val RULE_EXTENSIONS = setOf("md", "txt")
    }
}
