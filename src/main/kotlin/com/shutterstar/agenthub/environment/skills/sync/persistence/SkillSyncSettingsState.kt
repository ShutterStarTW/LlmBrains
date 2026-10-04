package com.shutterstar.agenthub.environment.skills.sync.persistence

data class SkillSyncSettingsState(
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    var preferredSyncMode: String = "SYMLINK",
    var backupBeforeReplacement: Boolean = true,
    // Added after schema version 1 shipped: an older persisted state simply lacks it and reads as false.
    var manageExistingTargets: Boolean = false,
    // Added later as well: an older persisted state lacks it and keeps the safe default (review first).
    var reviewPlanBeforeApplying: Boolean = true,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
