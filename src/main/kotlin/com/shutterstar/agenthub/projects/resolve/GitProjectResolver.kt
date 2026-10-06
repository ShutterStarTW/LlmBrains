package com.shutterstar.agenthub.projects.resolve

import com.shutterstar.agenthub.AgentRuntime
import java.nio.file.Path

data class GitProjectInfo(
    val root: String,
    val remote: String?,
    val currentBranch: String?,
)

class GitProjectResolver(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val commandRunner: (command: List<String>, timeoutMillis: Long) -> String? = ProcessCommandRunner::run,
) {
    fun resolve(projectPath: String): GitProjectInfo? {
        // A project that lives inside the WSL distro (a Linux path) is read from its files over the distro share:
        // Windows git cannot take a Linux path and refuses the share's ownership.
        if (AgentRuntime.isWsl() && projectPath.startsWith("/")) return resolveInDistro(projectPath)
        val root = git(projectPath, "rev-parse", "--show-toplevel") ?: return null
        val remote = git(root, "remote", "get-url", "origin")
        val branch = git(root, "rev-parse", "--abbrev-ref", "HEAD")
            ?.takeUnless { it == "HEAD" }
        return GitProjectInfo(
            root = root,
            remote = remote,
            currentBranch = branch,
        )
    }

    private fun resolveInDistro(linuxPath: String): GitProjectInfo? {
        val start = AgentRuntime.toHostPath(linuxPath)?.let { runCatching { Path.of(it) }.getOrNull() } ?: return null
        val metadata = GitMetadataReader.read(start) ?: return null
        val root = AgentRuntime.toLinuxPath(metadata.root.toString()) ?: return null
        return GitProjectInfo(root = root, remote = metadata.remote, currentBranch = metadata.currentBranch)
    }

    private fun git(workingDirectory: String, vararg arguments: String): String? =
        commandRunner(listOf("git", "-C", workingDirectory) + arguments, timeoutMillis)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 3_000L
    }
}
