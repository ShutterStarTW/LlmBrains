package com.shutterstar.agenthub.environment.config.model

enum class ConfigScope { GLOBAL, PROJECT }
enum class ConfigKind { SETTINGS, PERMISSIONS, HOOKS, OTHER }
enum class ConfigFormat { JSON, TOML, YAML, OTHER }
enum class ConfigRisk { NONE, WARNING }

data class ConfigHighlight(
    val key: String,
    val label: String,
    val value: String,
    val risk: ConfigRisk = ConfigRisk.NONE,
)

/** Inventory and explicitly allowed metadata only; raw configuration never leaves discovery. */
data class AgentConfigSource(
    val agentId: String,
    val path: String,
    val scope: ConfigScope,
    val kind: ConfigKind,
    val format: ConfigFormat,
    val exists: Boolean = true,
    val sizeBytes: Long = 0,
    val modifiedAtEpochMillis: Long = 0,
    val highlights: List<ConfigHighlight> = emptyList(),
    val projectName: String? = null,
)
