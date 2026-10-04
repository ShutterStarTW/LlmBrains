package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class OpenCodeInstructionProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
    private val majorVersion: () -> Int? = ::detectMajorVersion,
) : InstructionProvider {
    override val agentId: String = AGENT_ID
    private val globalFile = EnvHomeDirectorySupport.resolveXdgGuarded(
        "XDG_CONFIG_HOME",
        homeDirectory,
        ".config",
        "opencode",
    ).resolve(AGENTS_FILE)
    private val globalClaudeFile = homeDirectory.resolve(".claude").resolve(CLAUDE_FILE)
    private val usesClaudeFallback by lazy { majorVersion() != 2 }

    override fun discoverGlobal(): List<InstructionSource> = listOfNotNull(
        InstructionFileSupport.source(
            path = if (isNonEmptyFile(globalFile) || !usesClaudeFallback) globalFile else globalClaudeFile,
            scope = InstructionScope.GLOBAL,
            agentId = agentId,
            type = if (isNonEmptyFile(globalFile) || !usesClaudeFallback) InstructionType.AGENTS_MD else InstructionType.CLAUDE_MD,
        ),
    )

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val projectRoot = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        return InstructionFileSupport.scan(projectRoot) { _, file ->
            val fileName = file.fileName.toString()
            fileName.equals(AGENTS_FILE, ignoreCase = true) ||
                (usesClaudeFallback && fileName.equals(CLAUDE_FILE, ignoreCase = true))
        }.filterNot { file ->
            file.fileName.toString().equals(CLAUDE_FILE, ignoreCase = true) &&
                isNonEmptyFile(file.resolveSibling(AGENTS_FILE))
        }.mapNotNull { file ->
            val type = if (file.fileName.toString().equals(AGENTS_FILE, ignoreCase = true)) {
                InstructionType.AGENTS_MD
            } else {
                InstructionType.CLAUDE_MD
            }
            InstructionFileSupport.source(file, InstructionScope.PROJECT, agentId, type, project.name)
        }
    }

    private fun isNonEmptyFile(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { Files.size(path) > 0L }.getOrDefault(false)

    private companion object {
        const val AGENT_ID = "opencode"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"

        fun detectMajorVersion(): Int? = try {
            val process = ProcessBuilder("opencode", "--version").redirectErrorStream(true).start()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else {
                process.inputStream.bufferedReader().use { reader ->
                    Regex("\\b(\\d+)\\.\\d+").find(reader.readText())?.groupValues?.get(1)?.toIntOrNull()
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}
