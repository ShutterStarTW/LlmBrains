package com.shutterstar.agenthub.environment.skills.sync.model

import com.shutterstar.agenthub.environment.skills.sync.execution.SkillBackup
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import java.nio.file.Path

enum class SyncOperationStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    ROLLED_BACK,
}

data class SkillSyncStepResult(
    val step: SkillSyncStep,
    val succeeded: Boolean,
    val error: String? = null,
)

data class SyncError(
    val agentId: String?,
    val message: String,
)

enum class SyncTargetOutcome {
    CHANGED,
    ALREADY_AVAILABLE,
    BLOCKED,
    UNSUPPORTED,
    FAILED,
}

data class SkillSyncTargetResult(
    val agentId: String,
    val outcome: SyncTargetOutcome,
    val message: String? = null,
)

data class SkillSyncResult(
    val operationId: String,
    val status: SyncOperationStatus,
    val appliedSteps: List<SkillSyncStepResult>,
    val errors: List<SyncError>,
    val rollbackAvailable: Boolean,
    val restorableBackups: List<SkillBackup> = emptyList(),
    val skillId: String? = null,
    val previousManagedTargets: Map<String, ManagedTarget?> = emptyMap(),
    val instanceKey: SkillInstanceKey? = skillId?.let(SkillInstanceKey::legacy),
    val canonicalPath: Path? = null,
    val targetResults: List<SkillSyncTargetResult> = emptyList(),
    val rollbackErrors: List<SyncError> = emptyList(),
    /** Paths that existed before this operation and therefore require a retained backup for Undo. */
    val requiredBackupPaths: Set<Path> = emptySet(),
) {
    val recoveryRequired: Boolean get() = rollbackErrors.isNotEmpty()
}
