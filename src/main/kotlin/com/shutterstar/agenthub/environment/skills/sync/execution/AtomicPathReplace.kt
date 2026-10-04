package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.environment.skills.sync.link.LinkResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Stages new content beside [target], then swaps it into place with two same-directory
 * [StandardCopyOption.ATOMIC_MOVE]s. The live path is never deleted before the replacement is
 * fully built: a crash while staging leaves the original intact; a crash after displace leaves the
 * previous content at a recoverable sibling (`.…agenthub-displaced-…`) instead of destroying it.
 *
 * Used by [BackupService.restore] and by [SkillSyncStepExecutor] when a planned `RemoveExisting`
 * is immediately followed by `CreateLink`/`CopySkill` for the same path — those two plan steps are
 * executed as one install+swap so the delete-then-create gap cannot empty the skill directory.
 */
internal object AtomicPathReplace {
    fun replace(target: Path, install: (staging: Path) -> LinkResult): LinkResult {
        val parent = target.parent
            ?: return LinkResult.Failure("Cannot replace $target without a parent directory")

        val token = UUID.randomUUID().toString()
        val staging = parent.resolve(".${target.fileName}.agenthub-install-$token")
        val displaced = parent.resolve(".${target.fileName}.agenthub-displaced-$token")

        return runCatching {
            Files.createDirectories(parent)
            val staged = install(staging)
            if (staged !is LinkResult.Success) {
                runCatching { DirectoryDeleter.deleteRecursively(staging) }
                return staged
            }
            if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                return LinkResult.Failure("Install produced no content at $staging")
            }

            val hadOriginal = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            if (hadOriginal) {
                Files.move(target, displaced, StandardCopyOption.ATOMIC_MOVE)
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (error: Throwable) {
                if (hadOriginal &&
                    !Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
                    Files.exists(displaced, LinkOption.NOFOLLOW_LINKS)
                ) {
                    Files.move(displaced, target, StandardCopyOption.ATOMIC_MOVE)
                }
                throw error
            }
            if (hadOriginal) {
                runCatching { DirectoryDeleter.deleteRecursively(displaced) }
            }
            staged
        }.getOrElse { error ->
            runCatching { DirectoryDeleter.deleteRecursively(staging) }
            LinkResult.Failure(error.message ?: "Failed to replace $target")
        }
    }
}
