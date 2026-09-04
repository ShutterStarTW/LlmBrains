package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.CursorPluginDiscoverySupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

class CursorInstructionProvider(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) : InstructionProvider {
    override val agentId: String = AGENT_ID

    override fun discoverGlobal(): List<InstructionSource> {
        val budget = InstructionFileSupport.ScanBudget()
        return CursorPluginDiscoverySupport.discover(userHome)
            .asSequence()
            .flatMap { plugin ->
                CursorPluginDiscoverySupport.componentPaths(plugin, RULES_FIELD, RULES_DIRECTORY).asSequence()
            }
            .take(MAXIMUM_PLUGIN_COMPONENTS)
            .flatMap { discoverPluginRules(it, budget).asSequence() }
            .distinctBy(InstructionSource::path)
            .toList()
    }

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val projectRoot = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        return InstructionFileSupport.scan(projectRoot) { _, file ->
            val isAgentsFile = file.fileName.toString() == AGENTS_FILE
            val isClaudeFile = file == projectRoot.resolve(CLAUDE_FILE)
            val isCursorRule = file.extension.equals(MDC_EXTENSION, ignoreCase = true) &&
                InstructionFileSupport.isWithinDirectory(
                    projectRoot,
                    file,
                    CURSOR_DIRECTORY,
                    RULES_DIRECTORY,
                )
            isAgentsFile || isClaudeFile || isCursorRule
        }.mapNotNull { path ->
            val type = when (path.fileName.toString()) {
                AGENTS_FILE -> InstructionType.AGENTS_MD
                CLAUDE_FILE -> InstructionType.CLAUDE_MD
                else -> InstructionType.CURSOR_RULE
            }
            InstructionFileSupport.source(path, InstructionScope.PROJECT, agentId, type, project.name)
        }
    }

    private fun discoverPluginRules(
        path: Path,
        budget: InstructionFileSupport.ScanBudget,
    ): List<InstructionSource> {
        if (Files.isRegularFile(path)) {
            return if (path.extension.lowercase() in PLUGIN_RULE_EXTENSIONS) {
                listOfNotNull(
                    InstructionFileSupport.source(
                        path,
                        InstructionScope.GLOBAL,
                        agentId,
                        InstructionType.CURSOR_RULE,
                    ),
                )
            } else {
                emptyList()
            }
        }
        return InstructionFileSupport.scan(path, budget) { _, file ->
            file.extension.lowercase() in PLUGIN_RULE_EXTENSIONS
        }.mapNotNull { file ->
            InstructionFileSupport.source(
                file,
                InstructionScope.GLOBAL,
                agentId,
                InstructionType.CURSOR_RULE,
            )
        }
    }

    private companion object {
        const val AGENT_ID = "cursor"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val CURSOR_DIRECTORY = ".cursor"
        const val RULES_DIRECTORY = "rules"
        const val RULES_FIELD = "rules"
        const val MDC_EXTENSION = "mdc"
        const val MAXIMUM_PLUGIN_COMPONENTS = 512
        val PLUGIN_RULE_EXTENSIONS = setOf("md", "mdc", "markdown")
    }
}
