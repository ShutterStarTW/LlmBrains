package com.shutterstar.agenthub.environment.skills.ui

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal enum class ConflictFileStatus { MODIFIED, ADDED, MISSING }

internal data class ConflictFileEntry(val relativePath: String, val status: ConflictFileStatus)

/** Bounded, link-safe directory comparison used before an explicit conflict resolution. */
internal object SkillConflictDiff {
    private const val MAX_SCAN_DEPTH = 12
    private const val MAX_ENTRIES = 500
    private const val MAX_TEXT_BYTES = 2 * 1024 * 1024

    fun compareDirectories(canonicalDir: Path, targetDir: Path): List<ConflictFileEntry> {
        val canonicalFiles = listRelativeFiles(canonicalDir)
        val targetFiles = listRelativeFiles(targetDir)
        val allPaths = (canonicalFiles + targetFiles).sorted()
        val differences = allPaths.asSequence().mapNotNull { relativePath ->
            val inCanonical = relativePath in canonicalFiles
            val inTarget = relativePath in targetFiles
            when {
                inCanonical && !inTarget -> ConflictFileEntry(relativePath, ConflictFileStatus.MISSING)
                !inCanonical && inTarget -> ConflictFileEntry(relativePath, ConflictFileStatus.ADDED)
                else -> {
                    val canonicalFile = canonicalDir.resolve(relativePath)
                    val targetFile = targetDir.resolve(relativePath)
                    if (!Files.isRegularFile(canonicalFile, LinkOption.NOFOLLOW_LINKS) ||
                        !Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        throw IOException("A compared file changed or became a link: $relativePath")
                    }
                    if (Files.mismatch(canonicalFile, targetFile) != -1L) {
                        ConflictFileEntry(relativePath, ConflictFileStatus.MODIFIED)
                    } else {
                        null
                    }
                }
            }
        }.take(MAX_ENTRIES + 1).toList()
        if (differences.size > MAX_ENTRIES) {
            throw IOException("Skill contains too many differences for a safe conflict diff")
        }
        return differences
    }

    /** Reads one small, regular text file; symlinks, binary data and oversized content fail closed. */
    fun readText(dir: Path, relativePath: String): String {
        val root = dir.toAbsolutePath().normalize()
        val file = root.resolve(relativePath).normalize()
        require(file.startsWith(root)) {
            "Diff path escapes the skill directory"
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Diff content is not a regular file: $relativePath")
        }
        val bytes = Files.newInputStream(file).use { it.readNBytes(MAX_TEXT_BYTES + 1) }
        if (bytes.size > MAX_TEXT_BYTES) throw IOException("File is too large for the text diff: $relativePath")
        if (bytes.any { it == 0.toByte() }) throw IOException("Binary file cannot be shown as text: $relativePath")
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun listRelativeFiles(root: Path): Set<String> {
        val normalizedRoot = root.toAbsolutePath().normalize()
        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Skill directory is unavailable: $root")
        }
        val files = linkedSetOf<String>()
        Files.walkFileTree(
            normalizedRoot,
            emptySet(),
            MAX_SCAN_DEPTH,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (attributes.isOther || attributes.isSymbolicLink) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (attributes.isDirectory && Files.newDirectoryStream(file).use { it.iterator().hasNext() }) {
                        throw IOException("Skill contains directories deeper than the safe conflict diff limit")
                    }
                    if (!attributes.isSymbolicLink && !attributes.isOther && attributes.isRegularFile) {
                        files += normalizedRoot.relativize(file).toString().replace('\\', '/')
                        if (files.size > MAX_ENTRIES) {
                            throw IOException("Skill contains too many files for a safe conflict diff")
                        }
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return files
    }
}
