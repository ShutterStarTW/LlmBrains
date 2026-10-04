package com.shutterstar.agenthub.environment.persistence

data class EnvironmentIndexState(
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    var projects: MutableList<EnvironmentIndexProjectState> = mutableListOf(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class EnvironmentIndexProjectState(
    var projectId: String = "",
    var refreshedAtEpochMillis: Long = 0L,
    var agentIds: MutableList<String> = mutableListOf(),
    var skills: MutableList<EnvironmentIndexSkillState> = mutableListOf(),
    var mcpServers: MutableList<EnvironmentIndexMcpServerState> = mutableListOf(),
    var instructions: MutableList<EnvironmentIndexInstructionState> = mutableListOf(),
    var configs: MutableList<EnvironmentIndexConfigState> = mutableListOf(),
    var warnings: MutableList<EnvironmentIndexWarningState> = mutableListOf(),
)

data class EnvironmentIndexSkillState(
    var identityId: String = "",
    var name: String = "",
    var description: String? = null,
    var scope: String = "",
    var consistency: String = "",
    var compatibleAgents: MutableList<String> = mutableListOf(),
    var sources: MutableList<EnvironmentIndexSkillSourceState> = mutableListOf(),
)

data class EnvironmentIndexSkillSourceState(
    var agentId: String? = null,
    var path: String = "",
    var scope: String = "",
    var shared: Boolean = false,
    var fingerprint: String = "",
    var displayTitle: String? = null,
    var projectName: String? = null,
)

data class EnvironmentIndexMcpServerState(
    var id: String = "",
    var name: String = "",
    var transport: String = "",
    var environmentVariableNames: MutableList<String> = mutableListOf(),
    var scope: String = "",
    var consistency: String = "",
    var sources: MutableList<EnvironmentIndexMcpSourceState> = mutableListOf(),
)

data class EnvironmentIndexMcpSourceState(
    var agentId: String = "",
    var configPath: String = "",
    var sourceName: String = "",
    var projectName: String? = null,
)

data class EnvironmentIndexInstructionState(
    var path: String = "",
    var scope: String = "",
    var type: String = "",
    var agentIds: MutableList<String> = mutableListOf(),
    var projectName: String? = null,
)

data class EnvironmentIndexWarningState(
    var capability: String = "",
    var agentId: String? = null,
    var scope: String = "",
    var message: String = "",
)

/** No raw settings, hook commands or connection values are persisted. */
data class EnvironmentIndexConfigState(
    var agentId: String = "",
    var path: String = "",
    var scope: String = "",
    var kind: String = "",
    var format: String = "",
    var exists: Boolean = true,
    var sizeBytes: Long = 0,
    var modifiedAtEpochMillis: Long = 0,
    var projectName: String? = null,
    var highlights: MutableList<EnvironmentIndexConfigHighlightState> = mutableListOf(),
)

data class EnvironmentIndexConfigHighlightState(
    var key: String = "",
    var value: String = "",
)
