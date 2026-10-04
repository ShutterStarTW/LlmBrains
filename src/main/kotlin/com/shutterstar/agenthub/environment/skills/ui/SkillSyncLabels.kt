package com.shutterstar.agenthub.environment.skills.ui

import com.shutterstar.agenthub.environment.skills.sync.audit.SyncAction
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOperationStatus

/** Shared by [SkillSyncPreviewModel] and the History page so the two never drift apart. */
internal fun SyncAction.label(): String = when (this) {
    SyncAction.PROMOTE -> "Promote"
    SyncAction.SHARE -> "Share"
    SyncAction.SHARE_EVERYWHERE -> "Share everywhere"
    SyncAction.STOP_SHARING -> "Stop sharing"
    SyncAction.RESYNC -> "Resync"
    SyncAction.REPAIR -> "Repair"
    SyncAction.RESOLVE_CONFLICT -> "Resolve conflict for"
    SyncAction.RESTORE_BACKUP -> "Restore backup"
    SyncAction.UNDO -> "Undo"
    SyncAction.UPDATE_SHARING -> "Update sharing for"
    SyncAction.REPLACE_COPY -> "Replace a copy of"
    SyncAction.REMOVE_REDUNDANT_COPY -> "Remove redundant copy of"
}

internal fun SyncOperationStatus.label(): String = when (this) {
    SyncOperationStatus.SUCCESS -> "Succeeded"
    SyncOperationStatus.PARTIAL_SUCCESS -> "Partially succeeded"
    SyncOperationStatus.FAILED -> "Failed"
    SyncOperationStatus.ROLLED_BACK -> "Rolled back"
}
