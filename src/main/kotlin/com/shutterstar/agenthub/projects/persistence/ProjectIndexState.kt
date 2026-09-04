package com.shutterstar.agenthub.projects.persistence

data class ProjectIndexState(
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    var refreshedAtEpochMillis: Long = 0L,
    var projects: MutableList<ProjectIndexProjectState> = mutableListOf(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class ProjectIndexProjectState(
    var identityId: String = "",
    var name: String = "",
    var path: String? = null,
    var gitRoot: String? = null,
    var gitRemote: String? = null,
    var currentBranch: String? = null,
    var agents: MutableList<ProjectIndexAgentState> = mutableListOf(),
)

data class ProjectIndexAgentState(
    var agentId: String = "",
    var sessions: MutableList<ProjectIndexSessionState> = mutableListOf(),
)

data class ProjectIndexSessionState(
    var id: String = "",
    var projectPath: String? = null,
    var startedAtEpochMillis: Long? = null,
    var updatedAtEpochMillis: Long? = null,
    var sourcePath: String? = null,
    var nativeResumeId: String? = null,
)
