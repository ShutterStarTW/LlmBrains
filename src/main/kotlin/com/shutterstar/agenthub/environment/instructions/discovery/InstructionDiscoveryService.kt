package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.ProviderDiscoverySupport
import com.shutterstar.agenthub.environment.model.EnvironmentWarning
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.util.Locale
import java.util.logging.Logger

class InstructionDiscoveryService(
    private val providers: List<InstructionProvider> = listOf(
        AntigravityInstructionProvider(),
        ClaudeInstructionProvider(),
        ClineInstructionProvider(),
        CodexInstructionProvider(),
        CopilotInstructionProvider(),
        CursorInstructionProvider(),
        GrokInstructionProvider(),
        KiroInstructionProvider(),
        OpenCodeInstructionProvider(),
        QwenInstructionProvider(),
    ),
) {
    fun discoverGlobal(): List<InstructionSource> = normalize(discoverGlobalRecords())

    fun discoverProject(project: DiscoveredProject): List<InstructionSource> = normalize(discoverProjectRecords(project))

    fun discover(project: DiscoveredProject): List<InstructionSource> =
        normalize(discoverGlobalRecords() + discoverProjectRecords(project))

    fun discoverGlobalRecords(): List<InstructionSource> = discoverGlobalRecordsWithWarnings().first

    fun discoverProjectRecords(project: DiscoveredProject): List<InstructionSource> =
        discoverProjectRecordsWithWarnings(project).first

    fun discoverGlobalRecordsWithWarnings(): Pair<List<InstructionSource>, List<EnvironmentWarning>> =
        collect("global") { it.discoverGlobal() }

    fun discoverProjectRecordsWithWarnings(
        project: DiscoveredProject,
    ): Pair<List<InstructionSource>, List<EnvironmentWarning>> = collect("project") { it.discoverProject(project) }

    private fun collect(
        scope: String,
        discover: (InstructionProvider) -> List<InstructionSource>,
    ): Pair<List<InstructionSource>, List<EnvironmentWarning>> = ProviderDiscoverySupport.collect(
        providers = providers,
        capability = "instruction",
        scope = scope,
        agentId = InstructionProvider::agentId,
        logFailure = ::logFailure,
        discover = discover,
    )

    private fun logFailure(agentId: String?, scope: String, errorType: String) {
        LOG.warning("[InstructionDiscovery] ${agentId ?: "unknown"} $scope discovery failed: $errorType")
    }

    fun normalize(sources: List<InstructionSource>): List<InstructionSource> =
        sources
            .groupBy { source -> NormalizationKey(normalizePath(source.path), source.scope, source.type) }
            .map { (_, groupedSources) ->
                val representative = groupedSources.first()
                representative.copy(
                    agentIds = groupedSources.flatMapTo(sortedSetOf(), InstructionSource::agentIds),
                )
            }
            .sortedWith(
                compareBy<InstructionSource> { it.scope }
                    .thenBy { it.path.lowercase(Locale.ROOT) },
            )

    private fun normalizePath(path: String): String =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            path.lowercase(Locale.ROOT)
        } else {
            path
        }

    private data class NormalizationKey(
        val path: String,
        val scope: InstructionScope,
        val type: InstructionType,
    )

    companion object {
        private val LOG = Logger.getLogger(InstructionDiscoveryService::class.java.name)
    }
}
