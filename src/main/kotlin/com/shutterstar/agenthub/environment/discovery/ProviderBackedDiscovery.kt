package com.shutterstar.agenthub.environment.discovery

import com.shutterstar.agenthub.environment.model.EnvironmentWarning
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.util.logging.Logger

/**
 * The shared frame of the Skill / MCP / Instruction / Config discovery services: ask every visible
 * provider for its global or project records, isolating one provider's failure into a warning
 * ([ProviderDiscoverySupport.collect]). What the records are normalized into stays with each service.
 *
 * @param providerSource asked on every discovery call, so a default list that is rebuilt when the runtime changes
 *   (host / WSL distro) is picked up without recreating the service.
 * @param capability the name used in [EnvironmentWarning]s (`"skill"`, `"mcp"`, …).
 * @param logLabel the prefix of the failure log line (`"SkillDiscovery"`, …).
 * @param unknownAgentLabel what the log calls a provider without an agent id.
 */
abstract class ProviderBackedDiscovery<Provider : Any, Record>(
    private val providerSource: () -> List<Provider>,
    private val isAgentVisible: (String) -> Boolean,
    private val capability: String,
    private val logLabel: String,
    private val agentIdOf: (Provider) -> String?,
    private val unknownAgentLabel: String = "unknown",
) {
    constructor(
        providers: List<Provider>,
        isAgentVisible: (String) -> Boolean,
        capability: String,
        logLabel: String,
        agentIdOf: (Provider) -> String?,
        unknownAgentLabel: String = "unknown",
    ) : this({ providers }, isAgentVisible, capability, logLabel, agentIdOf, unknownAgentLabel)

    // Named after the concrete service, as before the frame was shared (tests and log filters rely on it).
    private val log = Logger.getLogger(javaClass.name)

    protected abstract fun discoverGlobalFrom(provider: Provider): List<Record>

    protected abstract fun discoverProjectFrom(provider: Provider, project: DiscoveredProject): List<Record>

    /** Providers without an agent id (the shared skill root) are decided by the subclass; others by [isAgentVisible]. */
    protected open fun isVisible(provider: Provider): Boolean = agentIdOf(provider)?.let(isAgentVisible) ?: true

    fun discoverGlobalRecords(): List<Record> = discoverGlobalRecordsWithWarnings().first

    fun discoverProjectRecords(project: DiscoveredProject): List<Record> =
        discoverProjectRecordsWithWarnings(project).first

    fun discoverGlobalRecordsWithWarnings(): Pair<List<Record>, List<EnvironmentWarning>> =
        collect("global") { discoverGlobalFrom(it) }

    fun discoverProjectRecordsWithWarnings(
        project: DiscoveredProject,
    ): Pair<List<Record>, List<EnvironmentWarning>> = collect("project") { discoverProjectFrom(it, project) }

    protected open fun collect(
        scope: String,
        discover: (Provider) -> List<Record>,
    ): Pair<List<Record>, List<EnvironmentWarning>> = ProviderDiscoverySupport.collect(
        providers = providerSource().filter(::isVisible),
        capability = capability,
        scope = scope,
        agentId = agentIdOf,
        logFailure = { agentId, failedScope, errorType ->
            log.warning("[$logLabel] ${agentId ?: unknownAgentLabel} $failedScope discovery failed: $errorType")
        },
        discover = discover,
    )
}
