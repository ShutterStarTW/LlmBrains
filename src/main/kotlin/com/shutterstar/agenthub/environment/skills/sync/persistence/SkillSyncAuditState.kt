package com.shutterstar.agenthub.environment.skills.sync.persistence

data class SkillSyncAuditState(
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    var entries: MutableList<SkillSyncAuditEntryState> = mutableListOf(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_ENTRIES = 1000
    }
}

data class SkillSyncAuditEntryState(
    var operationId: String = "",
    var timestampEpochMillis: Long = 0L,
    var skillId: String = "",
    var runtimeId: String = "",
    var scope: String = "",
    var contextPath: String = "",
    var action: String = "",
    var affectedAgents: MutableList<String> = mutableListOf(),
    var result: String = "",
)
