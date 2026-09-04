package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.AntigravityHomeSupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.extension

class AntigravityInstructionProvider(
    private val homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : InstructionProvider {
    override val agentId: String = AGENT_ID

    override fun discoverGlobal(): List<InstructionSource> {
        val sources = mutableListOf<InstructionSource>()
        val gemini = homeDirectory.resolve(GEMINI_DIRECTORY)

        val candidateFiles = listOfNotNull(
            gemini.resolve(GEMINI_FILE) to InstructionType.GEMINI_MD,
            gemini.resolve(AGENTS_FILE) to InstructionType.AGENTS_MD,
            gemini.resolve(CONFIG_DIRECTORY).resolve(GEMINI_FILE) to InstructionType.GEMINI_MD,
            gemini.resolve(CONFIG_DIRECTORY).resolve(AGENTS_FILE) to InstructionType.AGENTS_MD,
            gemini.resolve(CLI_DIRECTORY).resolve(GEMINI_FILE) to InstructionType.GEMINI_MD,
            gemini.resolve(CLI_DIRECTORY).resolve(AGENTS_FILE) to InstructionType.AGENTS_MD,
            gemini.resolve(ANTIGRAVITY_DIRECTORY).resolve(GEMINI_FILE) to InstructionType.GEMINI_MD,
            gemini.resolve(ANTIGRAVITY_DIRECTORY).resolve(AGENTS_FILE) to InstructionType.AGENTS_MD,
            AntigravityHomeSupport.configuredHome()?.resolve(GEMINI_FILE)?.let { it to InstructionType.GEMINI_MD },
            AntigravityHomeSupport.configuredHome()?.resolve(AGENTS_FILE)?.let { it to InstructionType.AGENTS_MD },
        )

        for ((file, type) in candidateFiles) {
            InstructionFileSupport.source(file, InstructionScope.GLOBAL, agentId, type)?.let {
                sources.add(it)
            }
        }

        val globalRuleDirs = listOfNotNull(
            gemini.resolve(RULES_DIRECTORY),
            gemini.resolve(CONFIG_DIRECTORY).resolve(RULES_DIRECTORY),
            AntigravityHomeSupport.configuredHome()?.resolve(RULES_DIRECTORY),
        )
        for (ruleDir in globalRuleDirs) {
            if (Files.isDirectory(ruleDir, LinkOption.NOFOLLOW_LINKS)) {
                sources.addAll(
                    InstructionFileSupport.scan(ruleDir) { _, file ->
                        file.extension.equals(MARKDOWN_EXTENSION, ignoreCase = true)
                    }.mapNotNull { path ->
                        InstructionFileSupport.source(
                            path,
                            InstructionScope.GLOBAL,
                            agentId,
                            InstructionType.ANTIGRAVITY_RULE,
                        )
                    },
                )
            }
        }

        return sources.distinctBy { it.path }
    }

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val projectRoot = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        return InstructionFileSupport.scan(projectRoot) { root, file ->
            val fileName = file.fileName.toString()
            fileName == AGENTS_FILE ||
                fileName == GEMINI_FILE ||
                (
                    file.extension.equals(MARKDOWN_EXTENSION, ignoreCase = true) &&
                        isRuleFile(root, file)
                )
        }.mapNotNull { path ->
            val type = when {
                isRuleFile(projectRoot, path) -> InstructionType.ANTIGRAVITY_RULE
                path.fileName.toString() == AGENTS_FILE -> InstructionType.AGENTS_MD
                else -> InstructionType.GEMINI_MD
            }
            InstructionFileSupport.source(path, InstructionScope.PROJECT, agentId, type, project.name)
        }.distinctBy { it.path }
    }

    private fun isRuleFile(root: Path, file: Path): Boolean =
        InstructionFileSupport.isWithinDirectory(root, file, AGENTS_DIRECTORY, RULES_DIRECTORY) ||
            InstructionFileSupport.isWithinDirectory(root, file, LEGACY_AGENT_DIRECTORY, RULES_DIRECTORY) ||
            InstructionFileSupport.isWithinDirectory(root, file, ALT_AGENTS_DIRECTORY, RULES_DIRECTORY) ||
            InstructionFileSupport.isWithinDirectory(root, file, ALT_AGENT_DIRECTORY, RULES_DIRECTORY) ||
            InstructionFileSupport.isWithinDirectory(root, file, GEMINI_DIRECTORY, RULES_DIRECTORY) ||
            isPluginRuleFile(file)

    private fun isPluginRuleFile(file: Path): Boolean {
        val parent = file.parent ?: return false
        if (!parent.fileName.toString().equals(RULES_DIRECTORY, ignoreCase = true)) return false
        val pluginDir = parent.parent ?: return false
        val pluginsDir = pluginDir.parent ?: return false
        if (!pluginsDir.fileName.toString().equals(PLUGINS_DIRECTORY, ignoreCase = true)) return false
        val rootDir = pluginsDir.parent ?: return false
        val rootName = rootDir.fileName.toString()
        return rootName.equals(AGENTS_DIRECTORY, ignoreCase = true) ||
            rootName.equals(LEGACY_AGENT_DIRECTORY, ignoreCase = true) ||
            rootName.equals(ALT_AGENTS_DIRECTORY, ignoreCase = true) ||
            rootName.equals(ALT_AGENT_DIRECTORY, ignoreCase = true) ||
            rootName.equals(GEMINI_DIRECTORY, ignoreCase = true)
    }

    private companion object {
        const val AGENT_ID = "antigravity"
        const val GEMINI_DIRECTORY = ".gemini"
        const val CONFIG_DIRECTORY = "config"
        const val CLI_DIRECTORY = "antigravity-cli"
        const val ANTIGRAVITY_DIRECTORY = "antigravity"
        const val AGENTS_DIRECTORY = ".agents"
        const val LEGACY_AGENT_DIRECTORY = ".agent"
        const val ALT_AGENTS_DIRECTORY = "_agents"
        const val ALT_AGENT_DIRECTORY = "_agent"
        const val RULES_DIRECTORY = "rules"
        const val PLUGINS_DIRECTORY = "plugins"
        const val AGENTS_FILE = "AGENTS.md"
        const val GEMINI_FILE = "GEMINI.md"
        const val MARKDOWN_EXTENSION = "md"
    }
}
