package com.shutterstar.agenthub.projects.resolve

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

    private fun git(workingDirectory: String, vararg arguments: String): String? =
        commandRunner(listOf("git", "-C", workingDirectory) + arguments, timeoutMillis)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 3_000L
    }
}
