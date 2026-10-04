package com.shutterstar.agenthub.environment.skills.sync.execution

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant

/**
 * Pure: given every [StoredBackupRecord] on disk, decides which ones a retention sweep should
 * delete. A backup is kept if it's among the most recent [MAX_KEPT_PER_TARGET] for its
 * (skill instance, agent) pair, *or* it's not yet [MAX_AGE] old — whichever keeps more. This means
 * the last few backups for a target are never pruned purely by age (a safety floor for Restore
 * Backup), while older ones beyond that floor eventually age out.
 */
internal object BackupRetentionPolicy {
    const val MAX_KEPT_PER_TARGET = 5
    val MAX_AGE: Duration = Duration.ofDays(30)

    fun recordsToDelete(records: List<StoredBackupRecord>, now: Instant): List<StoredBackupRecord> =
        records
            .groupBy { it.instanceKey to it.agentId }
            .values
            .flatMap { group ->
                group.sortedByDescending { it.backup.createdAt }
                    .drop(MAX_KEPT_PER_TARGET)
                    .filter { Duration.between(it.backup.createdAt, now) > MAX_AGE }
            }
}

/** Applies [BackupRetentionPolicy] to what's actually on disk under [backupRoot]. */
internal object BackupSweeper {
    fun sweep(backupRoot: Path, now: Instant = Instant.now()): Int {
        val normalizedRoot = backupRoot.toAbsolutePath().normalize()
        val records = BackupMetadataStore.listBackups(backupRoot)
        val toDelete = BackupRetentionPolicy.recordsToDelete(records, now)
            .filter { isSafeBackupPath(normalizedRoot, it.backup.backupPath) }
        var deleted = 0
        toDelete.forEach { record ->
            runCatching {
                DirectoryDeleter.deleteRecursively(record.backup.backupPath)
                Files.deleteIfExists(BackupMetadataStore.sidecarPathFor(record.backup.backupPath))
            }.onSuccess { deleted++ }
        }
        return deleted
    }

    private fun isSafeBackupPath(root: Path, candidate: Path): Boolean {
        val normalized = candidate.toAbsolutePath().normalize()
        if (normalized == root || !normalized.startsWith(root)) return false
        var current: Path? = normalized
        while (current != null && current != root) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                val attributes = runCatching {
                    Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                }.getOrNull() ?: return false
                if (attributes.isSymbolicLink || attributes.isOther) return false
            }
            current = current.parent
        }
        return current == root
    }
}
