package com.shutterstar.agenthub.environment.mcp.discovery

import com.shutterstar.agenthub.environment.discovery.ProviderDiscoverySupport
import com.shutterstar.agenthub.environment.model.EnvironmentWarning
import com.shutterstar.agenthub.environment.mcp.model.McpConsistency
import com.shutterstar.agenthub.environment.mcp.model.McpScope
import com.shutterstar.agenthub.environment.mcp.model.McpServer
import com.shutterstar.agenthub.environment.mcp.model.McpSource
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.logging.Logger

class McpDiscoveryService(
    private val providers: List<McpProvider> = listOf(
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
) {
    fun discoverGlobal(): List<McpServer> = normalize(discoverGlobalRecords())

    fun discoverProject(project: DiscoveredProject): List<McpServer> = normalize(discoverProjectRecords(project))

    fun discover(project: DiscoveredProject): List<McpServer> =
        normalize(discoverGlobalRecords() + discoverProjectRecords(project))

    fun discoverGlobalRecords(): List<RawMcpServer> = discoverGlobalRecordsWithWarnings().first

    fun discoverProjectRecords(project: DiscoveredProject): List<RawMcpServer> =
        discoverProjectRecordsWithWarnings(project).first

    fun discoverGlobalRecordsWithWarnings(): Pair<List<RawMcpServer>, List<EnvironmentWarning>> =
        collect("global") { it.discoverGlobal() }

    fun discoverProjectRecordsWithWarnings(
        project: DiscoveredProject,
    ): Pair<List<RawMcpServer>, List<EnvironmentWarning>> = collect("project") { it.discoverProject(project) }

    private fun collect(
        scope: String,
        discover: (McpProvider) -> List<RawMcpServer>,
    ): Pair<List<RawMcpServer>, List<EnvironmentWarning>> = ProviderDiscoverySupport.collect(
        providers = providers,
        capability = "mcp",
        scope = scope,
        agentId = McpProvider::agentId,
        logFailure = ::logFailure,
        discover = discover,
    )

    private fun logFailure(agentId: String?, scope: String, errorType: String) {
        LOG.warning("[McpDiscovery] ${agentId ?: "unknown"} $scope discovery failed: $errorType")
    }

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

    companion object {
        private val LOG = Logger.getLogger(McpDiscoveryService::class.java.name)
    }
}
