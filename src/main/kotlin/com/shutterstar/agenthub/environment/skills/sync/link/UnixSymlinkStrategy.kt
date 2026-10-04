package com.shutterstar.agenthub.environment.skills.sync.link

import com.shutterstar.agenthub.OsDetector
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import java.nio.file.Files
import java.nio.file.Path

class UnixSymlinkStrategy : FileLinkStrategy {
    override fun canLink(source: Path, target: Path): Boolean =
        !OsDetector.isWindows() &&
            Files.isDirectory(source)

    override fun createLink(source: Path, target: Path): LinkResult = runCatching {
        Files.createSymbolicLink(target, source)
        LinkResult.Success(EffectiveSyncMode.SYMLINK)
    }.getOrElse { error ->
        LinkResult.Failure(error.message ?: "Failed to create symlink")
    }
}
