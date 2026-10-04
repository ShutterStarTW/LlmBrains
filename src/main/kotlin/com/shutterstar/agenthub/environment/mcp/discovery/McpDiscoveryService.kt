package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.discovery.ProviderBackedDiscovery
import com.shutterstar.agenthub.environment.mcp.model.McpConsistency
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpServer
import com.shutterstar.agenthub.environment.mcp.model.McpSource
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

class McpDiscoveryService(
    providers: List<McpProvider> = listOf(
        AntigravityMcpProvider(),
        ClaudeMcpProvider(),
        ClineMcpProvider(),
        CodexMcpProvider(),
        CopilotMcpProvider(),
        CursorMcpProvider(),
        GrokMcpProvider(),
        KiroMcpProvider(),
        OpenCodeMcpProvider(),
        QwenMcpProvider(),
    ),
    /** Only installed agents are discovered. */
    isAgentVisible: (String) -> Boolean = { true },
) : ProviderBackedDiscovery<McpProvider, RawMcpServer>(
    providers, isAgentVisible, "mcp", "McpDiscovery", McpProvider::agentId,
) {
    fun discoverGlobal(): List<McpServer> = normalize(discoverGlobalRecords())

    fun discoverProject(project: DiscoveredProject): List<McpServer> = normalize(discoverProjectRecords(project))

    fun discover(project: DiscoveredProject): List<McpServer> =
        normalize(discoverGlobalRecords() + discoverProjectRecords(project))

    override fun discoverGlobalFrom(provider: McpProvider) = provider.discoverGlobal()

    override fun discoverProjectFrom(provider: McpProvider, project: DiscoveredProject) =
        provider.discoverProject(project)

    fun normalize(records: List<RawMcpServer>): List<McpServer> =
        records
            .groupBy { record -> NormalizationKey(normalizeName(record.name), record.scope) }
            .mapNotNull { (key, groupedRecords) -> normalizeGroup(key, groupedRecords) }
            .sortedWith(compareBy<McpServer> { it.name.lowercase(Locale.ROOT) }.thenBy { it.scope })

    private fun normalizeGroup(
        key: NormalizationKey,
        records: List<RawMcpServer>,
    ): McpServer? {
        if (key.normalizedName.isBlank()) return null
        val sortedRecords = records
            .distinctBy { listOf(it.agentId, it.configPath, it.name, it.scope) }
            .sortedWith(
                compareBy<RawMcpServer> { it.agentId }
                    .thenBy { it.configPath.lowercase(Locale.ROOT) },
            )
        val representative = sortedRecords.firstOrNull() ?: return null
        val consistency = when {
            sortedRecords.size == 1 -> McpConsistency.SINGLE_SOURCE
            sortedRecords.map(::configurationSignature).distinct().size == 1 -> McpConsistency.IDENTICAL
            else -> McpConsistency.DIFFERENT
        }

        return McpServer(
            id = createIdentity(key),
            name = representative.name.trim(),
            transport = representative.transport,
            command = representative.command,
            args = representative.args,
            url = representative.url,
            environmentVariableNames = sortedRecords
                .flatMapTo(linkedSetOf(), RawMcpServer::environmentVariableNames)
                .toSortedSet(),
            sources = sortedRecords.map { record ->
                McpSource(
                    agentId = record.agentId,
                    configPath = record.configPath,
                    sourceName = record.name,
                    projectName = record.projectName,
                )
            },
            scope = key.scope,
            consistency = consistency,
        )
    }

    private fun configurationSignature(server: RawMcpServer): String = buildString {
        append(server.transport.name)
        appendField(server.command?.trim().orEmpty())
        server.args.forEach { argument -> appendField(argument) }
        appendField(server.url?.trim().orEmpty())
        appendField(server.environmentFile?.trim().orEmpty())
        appendField(server.privateConfigurationFingerprint.orEmpty())
        server.environmentVariableNames.sorted().forEach { name -> appendField(name) }
    }

    private fun StringBuilder.appendField(value: String) {
        append('|')
        append(value.length)
        append(':')
        append(value)
    }

    private fun createIdentity(key: NormalizationKey): String {
        val input = "${key.scope.name}:${key.normalizedName}"
        return MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun normalizeName(name: String): String =
        name.trim().lowercase(Locale.ROOT)

    private data class NormalizationKey(
        val normalizedName: String,
        val scope: McpScope,
    )
}
