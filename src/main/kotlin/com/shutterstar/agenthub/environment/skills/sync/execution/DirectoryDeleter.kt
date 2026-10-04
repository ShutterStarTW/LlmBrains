package com.shutterstar.agenthub.environment.skills.sync.execution

import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes a symlink/junction as itself (never follows it into its target) or, for a plain
 * directory, deletes it recursively. Shared by [BackupService.restore] and
 * [SkillSyncStepExecutor]'s `RemoveExisting` handling so there is one delete implementation.
 */
internal object DirectoryDeleter {
    fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return

        if (Files.isSymbolicLink(path)) {
            Files.delete(path)
            return
        }

        // A Windows directory junction reports isDirectory()=true and isSymbolicLink()=false — the
        // JDK only recognizes IO_REPARSE_TAG_SYMLINK, not the IO_REPARSE_TAG_MOUNT_POINT tag
        // junctions use — so it's indistinguishable from a real directory by the check above. But
        // a junction is itself always empty (its target is reached only via the reparse point), so
        // a plain Files.delete() removes just the junction and never touches the target's content;
        // try that first. A genuinely non-empty real directory throws DirectoryNotEmptyException
        // and falls through to the recursive walk below.
        try {
            Files.delete(path)
            return
        } catch (_: DirectoryNotEmptyException) {
            // A real non-empty directory needs the guarded recursive walk below.
        }

        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (directory != path && attributes.isOther) {
                        // The Windows provider reports directory junctions as reparse-point
                        // directories (isOther=true), not symbolic links. Never descend into one:
                        // doing so would delete the junction target outside the skill tree.
                        Files.delete(directory)
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult {
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
