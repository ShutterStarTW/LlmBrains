package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.ProviderBackedDiscovery
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.util.Locale
import com.shutterstar.agenthub.OsDetector

class InstructionDiscoveryService(
    providers: List<InstructionProvider> = listOf(
        AntigravityInstructionProvider(),
        ClaudeInstructionProvider(),
        ClineInstructionProvider(),
        CodexInstructionProvider(),
        CopilotInstructionProvider(),
        CursorInstructionProvider(),
        FreebuffInstructionProvider(),
        GrokInstructionProvider(),
        JunieInstructionProvider(),
        KiloInstructionProvider(),
        KimiInstructionProvider(),
        KiroInstructionProvider(),
        MimoInstructionProvider(),
        OmpInstructionProvider(),
        OpenCodeInstructionProvider(),
        QwenInstructionProvider(),
        VibeInstructionProvider(),
    ),
    /** Only installed agents are discovered. */
    isAgentVisible: (String) -> Boolean = { true },
) : ProviderBackedDiscovery<InstructionProvider, InstructionSource>(
    providers, isAgentVisible, "instruction", "InstructionDiscovery", InstructionProvider::agentId,
) {
    fun discoverGlobal(): List<InstructionSource> = normalize(discoverGlobalRecords())

    fun discoverProject(project: DiscoveredProject): List<InstructionSource> = normalize(discoverProjectRecords(project))

    fun discover(project: DiscoveredProject): List<InstructionSource> =
        normalize(discoverGlobalRecords() + discoverProjectRecords(project))

    override fun discoverGlobalFrom(provider: InstructionProvider) = provider.discoverGlobal()

    override fun discoverProjectFrom(provider: InstructionProvider, project: DiscoveredProject) =
        provider.discoverProject(project)

    fun normalize(sources: List<InstructionSource>): List<InstructionSource> =
        sources
            .groupBy { source -> NormalizationKey(OsDetector.pathKey(source.path), source.scope, source.type) }
            .map { (_, groupedSources) ->
                val representative = groupedSources.first()
                representative.copy(
                    agentIds = groupedSources.flatMapTo(sortedSetOf(), InstructionSource::agentIds),
                    agentNotes = groupedSources.fold(emptyMap()) { merged, source -> merged + source.agentNotes },
                )
            }
            .sortedWith(
                compareBy<InstructionSource> { it.scope }
                    .thenBy { it.path.lowercase(Locale.ROOT) },
            )

    private data class NormalizationKey(
        val path: String,
        val scope: InstructionScope,
        val type: InstructionType,
    )
}
