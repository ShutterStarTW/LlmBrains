package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
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

    private val claudeDirectory = EnvHomeDirectorySupport.resolveGuarded("CLAUDE_CONFIG_DIR", homeDirectory, CLAUDE_DIRECTORY)

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
        val files = InstructionFileSupport.scan(projectRoot) { root, file ->
            val fileName = file.fileName.toString()
            fileName == CLAUDE_FILE ||
                fileName == CLAUDE_LOCAL_FILE ||
                fileName == AGENTS_FILE ||
                (
                    file.extension.equals(MARKDOWN_EXTENSION, ignoreCase = true) &&
                        InstructionFileSupport.isWithinDirectory(root, file, CLAUDE_DIRECTORY, RULES_DIRECTORY)
                    )
        }
        // Claude Code (v2.1.277+) reads AGENTS.md only as a fallback: when no CLAUDE.md,
        // .claude/CLAUDE.md or CLAUDE.local.md exists in the working directory (or above it);
        // in a subdirectory it applies only if that directory has none of them either.
        val root = projectRoot.toAbsolutePath().normalize()
        val claudeDirectories = files
            .filter { it.fileName.toString() == CLAUDE_FILE || it.fileName.toString() == CLAUDE_LOCAL_FILE }
            .map { it.instructionDirectory() }
            .toSet()
        val agentsRootShadowed = root in claudeDirectories
        return files.mapNotNull { path ->
            val name = path.fileName.toString()
            val type = when (name) {
                AGENTS_FILE -> InstructionType.AGENTS_MD
                CLAUDE_FILE, CLAUDE_LOCAL_FILE -> InstructionType.CLAUDE_MD
                else -> InstructionType.AGENT_SPECIFIC
            }
            val note = if (name == AGENTS_FILE) {
                agentsNotUsedReason(path, agentsRootShadowed, claudeDirectories)
            } else {
                null
            }
            InstructionFileSupport.source(path, InstructionScope.PROJECT, agentId, type, project.name, note)
        }
    }

    /** Why Claude Code does not apply this `AGENTS.md`, or null when it does (fallback). */
    private fun agentsNotUsedReason(path: Path, rootShadowed: Boolean, claudeDirectories: Set<Path>): String? = when {
        path.any { it.toString() == ".agents" } -> "Not used by Claude Code: it does not read files under .agents/."
        rootShadowed -> "Not used by Claude Code: it reads AGENTS.md only when no CLAUDE.md exists; the project's CLAUDE.md is used instead."
        path.instructionDirectory() in claudeDirectories ->
            "Not used by Claude Code: it reads AGENTS.md only when its directory has no CLAUDE.md; the CLAUDE.md there is used instead."
        else -> null
    }

    /** The directory an instruction file belongs to: `<dir>/CLAUDE.md` and `<dir>/.claude/CLAUDE.md` both map to `<dir>`. */
    private fun Path.instructionDirectory(): Path {
        val parent = parent ?: return this
        return if (parent.fileName?.toString() == CLAUDE_DIRECTORY) parent.parent ?: parent else parent
    }

    private companion object {
        const val AGENT_ID = "claude"
        const val CLAUDE_DIRECTORY = ".claude"
        const val RULES_DIRECTORY = "rules"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val CLAUDE_LOCAL_FILE = "CLAUDE.local.md"
        const val AGENTS_FILE = "AGENTS.md"
        const val MARKDOWN_EXTENSION = "md"
    }
}
