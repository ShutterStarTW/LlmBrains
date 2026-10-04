package com.shutterstar.agenthub.environment.skills.sync.persistence

data class SkillOwnershipState(
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    var entries: MutableList<SkillOwnershipEntryState> = mutableListOf(),
    /** Removal stamps prevent later imports from resurrecting older ownership records. */
    var removedEntries: MutableList<SkillOwnershipEntryState> = mutableListOf(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class SkillOwnershipEntryState(
    var skillId: String = "",
    var runtimeId: String = "",
    var scope: String = "",
    var contextPath: String = "",
    var agentId: String = "",
    var path: String = "",
    var requestedMode: String = "",
    var effectiveMode: String = "",
    var lastFingerprint: String? = null,
    var operationId: String? = null,
    var recordedAtEpochMillis: Long = 0L,
)
