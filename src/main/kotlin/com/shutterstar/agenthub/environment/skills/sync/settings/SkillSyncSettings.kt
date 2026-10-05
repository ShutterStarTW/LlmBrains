package com.shutterstar.agenthub.environment.skills.sync.settings

import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode

/**
 * Global defaults, editable via the Skills tab's settings dialog (spec §75). [preferredSyncMode]
 * pre-fills every `prepare*` request's [SkillSyncMode] (Share/Share Everywhere/Promote); a
 * per-request UI could still override it, but nothing does today. No conflict-policy field: §75
 * says auto-overwrite should not be offered initially, so "always ask" is the only option and
 * there's nothing to persist for it.
 */
data class SkillSyncSettings(
    val preferredSyncMode: SkillSyncMode = SkillSyncMode.SYMLINK,
    /**
     * Whether planning adds a [com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncStep.BackupExisting]
     * step before any Share/Repair/Resync/Promote/Stop Sharing/ResolveConflict(KEEP_CANONICAL,
     * KEEP_TARGET) replacement or removal — read once per `prepare*` call in
     * [com.shutterstar.agenthub.environment.skills.sync.SkillSyncApplicationService] and threaded
     * through [com.shutterstar.agenthub.environment.skills.sync.SkillSyncEngine.plan]. Turning it
     * off removes the safety net under Undo (which then only deletes what it just created, instead
     * of restoring what was replaced) and "Restore Backup…" (which has nothing to offer for an
     * operation that skipped its backup) — the settings dialog spells this out next to the toggle.
     */
    val backupBeforeReplacement: Boolean = true,
    /**
     * Whether AgentHub may also take away sharing it did not create: an existing link to a shared
     * skill, or a copy identical to it, when the user unchecks the agent in "Share with…" or presses
     * Stop Sharing. Off (the default) keeps the original rule — AgentHub only removes links and
     * copies it verifiably manages. On, such targets are removed too, but always after a backup
     * (even if [backupBeforeReplacement] is off) and never when the content differs from the shared
     * source, so nothing that only exists there can be lost. Only Stop Sharing is affected; Resync,
     * Repair and Share keep their ownership checks.
     */
    val manageExistingTargets: Boolean = false,
    /**
     * Whether a mutation shows its plan (preview / undo preview / bulk checklist) for approval before
     * anything changes. Off: the same plan is computed and applied straight away — backups are still
     * made, nothing about the planning rules changes, only the extra confirmation step is skipped.
     * Choices the user must make (which agents, which side of a conflict, which backup) are always asked.
     */
    val reviewPlanBeforeApplying: Boolean = true,
)
