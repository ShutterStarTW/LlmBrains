package com.shutterstar.agenthub

enum class InstallState { INSTALLED, NOT_INSTALLED, UNKNOWN }

/**
 * Pure rule for which CLI agents the AgentHub tool window may show: only those the latest
 * completed detection found installed. No IntelliJ imports, so it is unit-testable standalone.
 *
 * A missing result map and a missing agent id are both [InstallState.UNKNOWN], never
 * [InstallState.NOT_INSTALLED]: an incomplete or failed detection must not silently hide or
 * reveal data — it shows a "checking" state instead.
 */
object InstalledAgentPolicy {
    fun state(agentId: String, results: Map<String, Boolean>?): InstallState = when (results?.get(agentId)) {
        true -> InstallState.INSTALLED
        false -> InstallState.NOT_INSTALLED
        null -> InstallState.UNKNOWN
    }

    fun isVisible(agentId: String, results: Map<String, Boolean>?): Boolean =
        state(agentId, results) == InstallState.INSTALLED

    /** Ids from [candidates] that are visible under [results]; empty while nothing is known. */
    fun visibleIds(candidates: Collection<String>, results: Map<String, Boolean>?): Set<String> =
        candidates.filterTo(linkedSetOf()) { isVisible(it, results) }
}
