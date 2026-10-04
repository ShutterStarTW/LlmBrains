package com.shutterstar.agenthub.environment.skills.sync.audit

enum class SyncAction {
    PROMOTE,
    SHARE,
    SHARE_EVERYWHERE,
    STOP_SHARING,
    REPAIR,
    RESYNC,
    RESOLVE_CONFLICT,
    RESTORE_BACKUP,
    UNDO,
    UPDATE_SHARING,
    // Appended last: persisted audit entries store the action by name.
    REPLACE_COPY,
    REMOVE_REDUNDANT_COPY,
}
