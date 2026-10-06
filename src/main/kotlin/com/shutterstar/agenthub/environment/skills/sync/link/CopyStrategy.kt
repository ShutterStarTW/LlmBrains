package com.shutterstar.agenthub.environment.skills.sync.link

import com.shutterstar.agenthub.environment.skills.sync.execution.DirectoryDeleter
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.util.UUID

/**
 * Universal fallback when linking is unavailable. Never follows symlinks while walking the
 * source tree (default [Files.walkFileTree] behavior without `FOLLOW_LINKS`) so a symlink loop
 * inside a skill directory cannot cause unbounded recursion; a symlink entry is copied as a
 * symlink, not dereferenced.
 *
 * Copies into a staging directory next to [target] first, then [Files.move]s it into place with
 * [StandardCopyOption.ATOMIC_MOVE] — same parent directory means same file store, so the final
 * swap is a single atomic rename rather than the whole tree walk happening at the real path.
 * Callers that replace an occupied path must go through
 * [com.shutterstar.agenthub.environment.skills.sync.execution.AtomicPathReplace] (the executor
 * coalesces planned `RemoveExisting` + `CopySkill` pairs that way); this strategy itself refuses
 * an already-occupied [target] so a step-ordering bug cannot silently clobber live content. A
 * crash mid-copy leaves only an orphaned staging directory — never a half-written tree at the
 * path callers read from. The staging directory is best-effort cleaned up on any failure.
 */
class CopyStrategy : FileLinkStrategy {
    override fun canLink(source: Path, target: Path): Boolean = Files.isDirectory(source)

    override fun createLink(source: Path, target: Path): LinkResult = runCatching {
        // Files.move(..., ATOMIC_MOVE) does not reliably fail just because REPLACE_EXISTING is
        // absent — on at least one platform it silently replaced a pre-existing plain file at
        // target in testing. Occupied targets must be swapped via AtomicPathReplace; this check
        // fails loudly on a step-ordering bug or race instead of clobbering live content.
        check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Refusing to replace existing content at $target" }
        val staging = target.resolveSibling("${target.fileName}.agenthub-tmp-${UUID.randomUUID()}")
        try {
            copyTree(source, staging)
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: Throwable) {
            runCatching { DirectoryDeleter.deleteRecursively(staging) }
            throw error
        }
        LinkResult.Success(EffectiveSyncMode.COPY)
    }.getOrElse { error ->
        LinkResult.Failure(error.message ?: "Failed to copy skill directory")
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (attributes.isOther) {
                        // Windows junctions are directory reparse points, not Java symbolic links.
                        // Following one would copy data from outside the reviewed skill tree.
                        throw IOException("Refusing to copy directory reparse point: $directory")
                    }
                    Files.createDirectories(target.resolve(source.relativize(directory)))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (attributes.isOther && !attributes.isSymbolicLink) {
                        throw IOException("Refusing to copy filesystem reparse point: $file")
                    }
                    val destination = target.resolve(source.relativize(file))
                    try {
                        Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                    } catch (_: NotImplementedError) {
                        // The IDE's WSL file system does not implement every option; the walk above already
                        // refused anything that is not a plain file or a link, so the plain copy is equivalent.
                        Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                    preservePosixPermissions(file, destination)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun preservePosixPermissions(source: Path, destination: Path) {
        runCatching {
            val sourceView = Files.getFileAttributeView(source, PosixFileAttributeView::class.java) ?: return
            val destinationView = Files.getFileAttributeView(destination, PosixFileAttributeView::class.java) ?: return
            destinationView.setPermissions(sourceView.readAttributes().permissions())
        }
    }
}
