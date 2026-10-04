package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.ScanBudget
import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.io.path.name

/**
 * Discovers Cursor CLI sessions from `meta.json`, plus the user's own prompts from
 * `prompt_history.json`; the conversation database (`store.db`) is never opened.
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
            LocalSessionSupport.listDirectories(chatsDirectory, MAX_WORKSPACE_ENTRIES).flatMap { workspaceDirectory ->
                LocalSessionSupport.listDirectories(workspaceDirectory, budget).mapNotNull { sessionDirectory ->
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
        val statistics = SessionStatisticsAccumulator(agentId)
        statistics.recordedBounds(startedAt, recordedUpdate)
        val prompts = userMessages(metadataFile.resolveSibling(PROMPT_HISTORY_FILE), statistics)
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = projectPath,
            sessionId = sessionId,
            startedAt = startedAt,
            updatedAt = LocalSessionSupport.latest(startedAt, updatedAt),
            sourcePath = metadataFile.toAbsolutePath().normalize().toString(),
            metadata = title?.let { mapOf(TITLE_FIELD to it) }.orEmpty() +
                prompts?.metadata().orEmpty(),
            statistics = statistics.snapshot().filterKeys { it != "editTurns" },
        )
    }

    /** `prompt_history.json` is a JSON array of the prompts typed in this chat, newest first. */
    private fun userMessages(promptHistory: Path, statistics: SessionStatisticsAccumulator): UserMessageTally? {
        if (!Files.isRegularFile(promptHistory, LinkOption.NOFOLLOW_LINKS)) return null
        val json = runCatching { LocalSessionSupport.readBoundedText(promptHistory, MAX_PROMPT_HISTORY_CHARACTERS) }
            .getOrNull() ?: return null
        val prompts = MetadataJsonParser.arrayElements(json) ?: return null
        return UserMessageTally().apply {
            prompts.asReversed().forEach {
                val text = MessageContentExtractor.decodeString(it)
                add(text)
                statistics.userPrompt(text)
            }
        }
    }

    companion object {
        private const val AGENT_ID = "cursor"
        private const val CHATS_DIRECTORY = "chats"
        private const val METADATA_FILE = "meta.json"
        private const val PROMPT_HISTORY_FILE = "prompt_history.json"
        private const val MAX_PROMPT_HISTORY_CHARACTERS = 8 * 1024 * 1024
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
