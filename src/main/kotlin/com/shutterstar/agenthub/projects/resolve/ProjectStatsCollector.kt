package com.shutterstar.agenthub.projects.resolve

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

data class ProjectStats(
    val fileCount: Int,
    val fileCountTruncated: Boolean,
    val totalSizeBytes: Long,
    val commitCount: Int?,
)

/**
 * Walks a project directory for a lightweight Overview summary (file count, disk size, and —
 * when the project is a Git repo — total commit count). Deliberately excludes common vendor/build
 * directories so a monorepo's node_modules/.git/build noise doesn't dominate the count, and caps
 * the walk so a huge project degrades to "N+ files" instead of running away — this always runs off
 * the EDT (see ProjectDetailsPanel), but an unbounded walk could still tie up the shared app
 * executor for a long time on a very large tree.
 */
object ProjectStatsCollector {
    private const val MAX_ENTRIES = 20_000
    private const val COMMIT_COUNT_TIMEOUT_MILLIS = 5_000L
    private val EXCLUDED_DIR_NAMES = setOf(
        ".git", "node_modules", "build", "dist", "target", "out",
        ".gradle", ".idea", "venv", ".venv", "__pycache__", "vendor", ".mvn",
    )

    fun collect(
        path: String,
        gitRoot: String?,
        commandRunner: (command: List<String>, timeoutMillis: Long) -> String? = ProcessCommandRunner::run,
    ): ProjectStats {
        val (fileCount, truncated, totalSize) = walk(path)
        val commitCount = gitRoot?.let { root ->
            commandRunner(listOf("git", "-C", root, "rev-list", "--count", "HEAD"), COMMIT_COUNT_TIMEOUT_MILLIS)
                ?.trim()?.toIntOrNull()
        }
        return ProjectStats(fileCount, truncated, totalSize, commitCount)
    }

    private fun walk(path: String): Triple<Int, Boolean, Long> {
        val root = try {
            Paths.get(path)
        } catch (_: Exception) {
            return Triple(0, false, 0L)
        }
        if (!Files.isDirectory(root)) return Triple(0, false, 0L)
        var count = 0
        var size = 0L
        var truncated = false
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                        if (dir != root && dir.fileName?.toString() in EXCLUDED_DIR_NAMES) {
                            FileVisitResult.SKIP_SUBTREE
                        } else {
                            FileVisitResult.CONTINUE
                        }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        count++
                        size += attrs.size()
                        if (count >= MAX_ENTRIES) {
                            truncated = true
                            return FileVisitResult.TERMINATE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                },
            )
        } catch (_: IOException) {
            // Best-effort — a partial count from whatever was walked before the failure is still
            // more useful to show than nothing.
        }
        return Triple(count, truncated, size)
    }
}
