package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.io.path.name

/**
 * Discovers Cursor CLI sessions from metadata files without reading conversation transcripts or databases.
 */
class CursorProjectProvider(
    private val cursorDirectory: Path = defaultCursorDirectory(),
    private val maxScanEntries: Int = MAX_SCAN_ENTRIES,
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val chatsDirectory = cursorDirectory.resolve(CHATS_DIRECTORY)

    override fun isAvailable(): Boolean = Files.isDirectory(chatsDirectory, LinkOption.NOFOLLOW_LINKS)

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()
        var skippedRecords = 0
        val budget = ScanBudget(maxScanEntries)
        val sessions = runCatching {
            listDirectories(chatsDirectory, MAX_WORKSPACE_ENTRIES).flatMap { workspaceDirectory ->
                listDirectories(workspaceDirectory, budget).mapNotNull { sessionDirectory ->
                    val metadataFile = sessionDirectory.resolve(METADATA_FILE)
                    if (!Files.isRegularFile(metadataFile, LinkOption.NOFOLLOW_LINKS)) {
                        return@mapNotNull null
                    }
                    runCatching { parseSession(metadataFile) }
                        .getOrNull()
                        .also { if (it == null) skippedRecords++ }
                }
            }
        }.getOrDefault(emptyList())
        if (skippedRecords > 0) {
            LOG.fine("[ProjectDiscovery] Cursor: skipped $skippedRecords invalid or empty metadata records")
        }
        return LocalSessionSupport.deduplicate(sessions)
    }

    private fun listDirectories(
        root: Path,
        budget: ScanBudget,
    ): List<Path> {
        if (!budget.hasRemaining()) return emptyList()
        return listDirectories(root, budget.remaining()).also { budget.consume(it.size) }
    }

    private fun listDirectories(
        root: Path,
        maximumEntries: Int,
    ): List<Path> =
        Files.list(root).use { paths ->
            paths
                .limit(maximumEntries.coerceAtLeast(0).toLong())
                .toList()
                .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted()
        }

    private fun parseSession(metadataFile: Path): RawAgentProject? {
        val json = LocalSessionSupport.readBoundedText(metadataFile, MAX_JSON_CHARACTERS) ?: return null
        val fields = MetadataJsonParser.topLevelStringFields(json, STRING_FIELDS)
        val timestamps = MetadataJsonParser.topLevelLongFields(json, TIMESTAMP_FIELDS)
        val flags = MetadataJsonParser.topLevelBooleanFields(json, BOOLEAN_FIELDS)
        if (flags[HAS_CONVERSATION_FIELD] == false) return null

        val projectPath = fields[CWD_FIELD]?.takeIf { it.isNotBlank() } ?: return null
        val sessionId = metadataFile.parent?.name?.takeIf { it.isNotBlank() } ?: return null
        val startedAt = LocalSessionSupport.epochTimestamp(timestamps[CREATED_AT_FIELD])
        val recordedUpdate = LocalSessionSupport.epochTimestamp(timestamps[UPDATED_AT_FIELD])
        val updatedAt = recordedUpdate ?: LocalSessionSupport.modifiedAt(metadataFile)
        val title = fields[TITLE_FIELD]?.takeIf { it.isNotBlank() }
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = projectPath,
            sessionId = sessionId,
            startedAt = startedAt,
            updatedAt = LocalSessionSupport.latest(startedAt, updatedAt),
            sourcePath = metadataFile.toAbsolutePath().normalize().toString(),
            metadata = title?.let { mapOf(TITLE_FIELD to it) }.orEmpty(),
        )
    }

    companion object {
        private const val AGENT_ID = "cursor"
        private const val CHATS_DIRECTORY = "chats"
        private const val METADATA_FILE = "meta.json"
        private const val MAX_SCAN_ENTRIES = 20_000
        private const val MAX_WORKSPACE_ENTRIES = 4_096
        private const val MAX_JSON_CHARACTERS = 256 * 1024
        private const val CREATED_AT_FIELD = "createdAtMs"
        private const val UPDATED_AT_FIELD = "updatedAtMs"
        private const val CWD_FIELD = "cwd"
        private const val TITLE_FIELD = "title"
        private const val HAS_CONVERSATION_FIELD = "hasConversation"
        private val STRING_FIELDS = setOf(CWD_FIELD, TITLE_FIELD)
        private val TIMESTAMP_FIELDS = setOf(CREATED_AT_FIELD, UPDATED_AT_FIELD)
        private val BOOLEAN_FIELDS = setOf(HAS_CONVERSATION_FIELD)
        private val LOG = Logger.getLogger(CursorProjectProvider::class.java.name)

        private fun defaultCursorDirectory(): Path =
            Path.of(System.getProperty("user.home"), ".cursor")
    }
}
