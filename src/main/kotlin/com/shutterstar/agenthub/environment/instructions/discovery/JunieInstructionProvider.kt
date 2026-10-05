package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Junie CLI guidelines (docs: junie.jetbrains.com/docs/guidelines-and-memory.html): `.junie/AGENTS.md`, the project-root
 * `AGENTS.md` together with `.junie/playbook.md` and the Markdown files of `.junie/rules/`, the legacy `.junie/guidelines.md` /
 * `.junie/guidelines/` folder, and the global `~/.junie/AGENTS.md` (`JUNIE_HOME` replaces `~/.junie`). Which of the
 * project files wins when several exist is Junie's rule and is not asserted here: every file found is listed.
 */
class JunieInstructionProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : InstructionProvider {
    override val agentId: String = AGENT_ID
    private val junieHome = EnvHomeDirectorySupport.resolveGuarded("JUNIE_HOME", homeDirectory, JUNIE_DIRECTORY)

    override fun discoverGlobal(): List<InstructionSource> = listOfNotNull(
        InstructionFileSupport.source(junieHome.resolve(AGENTS_FILE), InstructionScope.GLOBAL, agentId, InstructionType.AGENTS_MD),
    )

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val root = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        val junie = root.resolve(JUNIE_DIRECTORY)
        val files = buildList {
            add(junie.resolve(AGENTS_FILE) to InstructionType.AGENTS_MD)
            add(root.resolve(AGENTS_FILE) to InstructionType.AGENTS_MD)
            add(junie.resolve(PLAYBOOK_FILE) to InstructionType.AGENT_SPECIFIC)
            markdownFiles(junie.resolve(RULES_DIRECTORY)).forEach { add(it to InstructionType.AGENT_SPECIFIC) }
            add(junie.resolve(LEGACY_GUIDELINES_FILE) to InstructionType.AGENT_SPECIFIC)
            markdownFiles(junie.resolve(LEGACY_GUIDELINES_DIRECTORY)).forEach { add(it to InstructionType.AGENT_SPECIFIC) }
        }
        return files.mapNotNull { (file, type) ->
            InstructionFileSupport.source(file, InstructionScope.PROJECT, agentId, type, project.name)
        }
    }

    /** The `*.md` files directly inside [directory] (Junie's `rules/` and `guidelines/` folders are flat lists). */
    private fun markdownFiles(directory: Path): List<Path> {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return runCatching {
            Files.list(directory).use { entries ->
                entries
                    .limit(MAX_FILES)
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".md", ignoreCase = true) }
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val AGENT_ID = "junie"
        const val JUNIE_DIRECTORY = ".junie"
        const val AGENTS_FILE = "AGENTS.md"
        const val PLAYBOOK_FILE = "playbook.md"
        const val RULES_DIRECTORY = "rules"
        const val LEGACY_GUIDELINES_FILE = "guidelines.md"
        const val LEGACY_GUIDELINES_DIRECTORY = "guidelines"
        const val MAX_FILES = 500L
    }
}
