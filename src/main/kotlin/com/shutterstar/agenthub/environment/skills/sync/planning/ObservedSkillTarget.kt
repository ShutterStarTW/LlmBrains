package com.shutterstar.agenthub.environment.skills.sync.planning

import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOwner
import java.nio.file.Path

data class ObservedSkillTarget(
    val agentId: String,
    val targetPath: Path?,
    val status: SkillTargetStatus,
    val requestedMode: SkillSyncMode = SkillSyncMode.SYMLINK,
    val availableLinkMode: EffectiveSyncMode? = null,
    val fingerprint: String? = null,
    val ownershipVerified: Boolean = false,
    val managedMode: EffectiveSyncMode? = null,
    val managedLinkTarget: Path? = null,
    /** A still-existing previously managed path when the adapter now resolves a different path. */
    val renameCandidatePath: Path? = null,
) {
    /**
     * Derived, not stored — [SkillTargetObserver] already computes [ownershipVerified] from every
     * signal this needs, so a stand-alone field would just be another way to say the same thing
     * and could drift out of sync with it. See [SyncOwner]'s own doc comment for why [SyncOwner.EXTERNAL]
     * never comes out of this.
     */
    val owner: SyncOwner get() = when (status) {
        SkillTargetStatus.NOT_AVAILABLE,
        SkillTargetStatus.UNSUPPORTED,
        SkillTargetStatus.MISSING_SOURCE,
        SkillTargetStatus.ERROR,
        -> SyncOwner.UNKNOWN
        SkillTargetStatus.NATIVE -> SyncOwner.NATIVE
        else -> if (ownershipVerified) SyncOwner.AGENTHUB else SyncOwner.MANUAL
    }
}
