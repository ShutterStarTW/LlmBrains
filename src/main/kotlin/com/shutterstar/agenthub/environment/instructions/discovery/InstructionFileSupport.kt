package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.PROJECT_WALK_EXCLUDED_DIRECTORY_NAMES
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal object InstructionFileSupport {
    private val excludedDirectoryNames = PROJECT_WALK_EXCLUDED_DIRECTORY_NAMES

    fun projectRoot(project: DiscoveredProject): Path? = ProjectPathResolver.resolveExistingRoot(project)

    fun source(
        path: Path,
        scope: InstructionScope,
        agentId: String,
        type: InstructionType,
        projectName: String? = null,
    ): InstructionSource? {
        if (!isNonEmptyRegularFile(path)) return null
        return InstructionSource(
            path = path.toAbsolutePath().normalize().toString(),
            scope = scope,
            agentIds = setOf(agentId),
            type = type,
            projectName = projectName,
        )
    }

    fun scan(
        root: Path,
        budget: ScanBudget = ScanBudget(),
        matcher: (root: Path, file: Path) -> Boolean,
    ): List<Path> {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val matches = mutableListOf<Path>()
        runCatching {
            Files.walkFileTree(
                root,
                emptySet(),
                MAXIMUM_DEPTH,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                        if (directory != root && directory.fileName.toString().lowercase() in excludedDirectoryNames) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return if (!budget.consume()) {
                            FileVisitResult.TERMINATE
                        } else {
                            FileVisitResult.CONTINUE
                        }
                    }

                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        if (!budget.consume()) return FileVisitResult.TERMINATE
                        if (attributes.isRegularFile && attributes.size() > 0L && matcher(root, file)) {
                            matches.add(file.toAbsolutePath().normalize())
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
        return matches.distinct().sortedBy { it.toString().lowercase() }
    }

    private fun isWithinDirectory(file: Path, directoryName: String, childName: String): Boolean {
        val segments = file.iterator().asSequence().map { it.toString().lowercase() }.toList()
        return segments.windowed(2).any { (first, second) ->
            first == directoryName.lowercase() && second == childName.lowercase()
        }
    }

    fun isWithinDirectory(
        root: Path,
        file: Path,
        directoryName: String,
        childName: String,
    ): Boolean {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedFile = file.toAbsolutePath().normalize()
        if (!normalizedFile.startsWith(normalizedRoot)) return false
        return isWithinDirectory(normalizedRoot.relativize(normalizedFile), directoryName, childName)
    }

    private fun isNonEmptyRegularFile(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { Files.size(path) > 0L }.getOrDefault(false)

    private const val MAXIMUM_DEPTH = 12
    private const val MAXIMUM_ENTRIES = 20_000

    class ScanBudget(
        private var remaining: Int = MAXIMUM_ENTRIES,
    ) {
        fun consume(): Boolean {
            if (remaining <= 0) return false
            remaining--
            return true
        }
    }
}
