package com.shutterstar.agenthub.projects.resolve

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** What a repository's own files say: where its root is, the `origin` URL and the checked-out branch. */
data class GitMetadata(val root: Path, val remote: String?, val currentBranch: String?)

/**
 * Reads a repository's metadata straight from `.git` (no `git` process): `HEAD` for the branch and `config` for the
 * `origin` URL. Used where starting git is not an option - a WSL project is reachable from Windows only as files
 * on the distro share, and Windows git on a `\wsl.localhost` path fails ("dubious ownership").
 *
 * Handles a plain `.git` directory and a `.git` *file* (`gitdir: ...`, a worktree or submodule). Everything that
 * cannot be read is simply absent.
 */
object GitMetadataReader {
    private const val MAX_LEVELS = 64
    private const val MAX_FILE_BYTES = 256 * 1024L

    fun read(start: Path): GitMetadata? {
        var directory: Path? = start.toAbsolutePath().normalize()
        var levels = 0
        while (directory != null && levels++ < MAX_LEVELS) {
            val dotGit = directory.resolve(".git")
            val gitDirectory = when {
                Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS) -> dotGit
                Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS) -> gitDirectoryFromFile(dotGit, directory)
                else -> null
            }
            if (gitDirectory != null) return metadata(directory, gitDirectory)
            directory = directory.parent
        }
        return null
    }

    private fun gitDirectoryFromFile(dotGit: Path, root: Path): Path? {
        val line = readText(dotGit)?.lineSequence()?.firstOrNull { it.startsWith("gitdir:") } ?: return null
        val target = line.removePrefix("gitdir:").trim().takeIf { it.isNotEmpty() } ?: return null
        return runCatching { root.resolve(target).normalize() }.getOrNull()
            ?.takeIf { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
    }

    private fun metadata(root: Path, gitDirectory: Path): GitMetadata {
        // A linked worktree keeps HEAD in its own directory but shares config with the main one (`commondir`).
        val commonDirectory = readText(gitDirectory.resolve("commondir"))?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { gitDirectory.resolve(it).normalize() }.getOrNull() }
            ?: gitDirectory
        val branch = readText(gitDirectory.resolve("HEAD"))?.lineSequence()?.firstOrNull()?.trim()
            ?.takeIf { it.startsWith("ref:") }
            ?.removePrefix("ref:")?.trim()
            ?.takeIf { it.startsWith("refs/heads/") }
            ?.removePrefix("refs/heads/")
        return GitMetadata(root, originUrl(readText(commonDirectory.resolve("config"))), branch)
    }

    /** The `url` of `[remote "origin"]` in git's INI-like config text. */
    internal fun originUrl(config: String?): String? {
        config ?: return null
        var inOrigin = false
        for (raw in config.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inOrigin = Regex("""^\[\s*remote\s+"origin"\s*]""").containsMatchIn(line)
                continue
            }
            if (inOrigin && line.startsWith("url")) {
                val value = line.substringAfter('=', "").trim().trim('"')
                if (value.isNotEmpty() && line.substringBefore('=').trim() == "url") return value
            }
        }
        return null
    }

    private fun readText(file: Path): String? = runCatching {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE_BYTES) null
        else Files.readString(file)
    }.getOrNull()
}
