package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.latest
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.readTailLines
import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.logging.Logger
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import com.shutterstar.agenthub.SafeFileTree

class CodexProjectProvider(
    private val codexDirectory: Path = defaultCodexDirectory(),
    private val maxScanEntries: Int = MAX_SCAN_ENTRIES,
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val sessionDirectories = listOf(
        codexDirectory.resolve(SESSIONS_DIRECTORY),
        codexDirectory.resolve(ARCHIVED_SESSIONS_DIRECTORY),
    )

    override fun isAvailable(): Boolean = sessionDirectories.any {
        Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)
    }

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()

        var skippedSessions = 0
        var remainingScanEntries = maxScanEntries.coerceAtLeast(0)
        val sessions = mutableListOf<RawAgentProject>()
        val titles = sessionTitles()
        sessionDirectories
            .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            .forEach { sessionDirectory ->
                if (remainingScanEntries == 0) return@forEach
                var scannedEntries = 0
                SafeFileTree.walk(sessionDirectory, MAX_SCAN_DEPTH).use { paths ->
                    paths
                        .skip(1)
                        .limit(remainingScanEntries.toLong())
                        .peek { scannedEntries++ }
                        .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                        .filter { it.extension.equals(JSONL_EXTENSION, ignoreCase = true) }
                        .sorted()
                        .forEach { sessionFile ->
                            val session = runCatching { parseSession(sessionFile) }.getOrNull()
                            if (session == null) {
                                skippedSessions++
                            } else {
                                sessions += titles[session.sessionId]?.let { title ->
                                    session.copy(metadata = session.metadata + ("title" to title))
                                } ?: session
                            }
                        }
                }
                remainingScanEntries -= scannedEntries
            }
        if (skippedSessions > 0) {
            LOG.fine("[ProjectDiscovery] Codex: skipped $skippedSessions malformed or unreadable sessions")
        }
        return LocalSessionSupport.deduplicate(sessions)
    }

    /** Codex keeps user-assigned thread names outside the rollout, in an append-only index. */
    private fun sessionTitles(): Map<String, String?> {
        val index = codexDirectory.resolve(SESSION_INDEX_FILE)
        if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) return emptyMap()
        val titles = linkedMapOf<String, String?>()
        runCatching { readTailLines(index, MAX_INDEX_BYTES, MAX_INDEX_LINES) }.getOrDefault(emptyList())
            .forEach { line ->
                val fields = MetadataJsonParser.topLevelStringFields(line, INDEX_FIELDS)
                val id = fields[ID_FIELD]?.takeIf { it.isNotBlank() } ?: return@forEach
                if (id !in titles) {
                    titles[id] = fields[THREAD_NAME_FIELD]
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.take(MAX_TITLE_CHARACTERS)
                }
            }
        return titles
    }

    private fun parseSession(sessionFile: Path): RawAgentProject? {
        var metadata: SessionMetadata? = null
        LocalSessionSupport.scanHeaderLines(sessionFile, MAX_HEADER_LINES, MAX_LINE_CHARACTERS) { text ->
            val topLevel = MetadataJsonParser.topLevelStringFields(text, TOP_LEVEL_FIELDS)
            if (topLevel[TYPE_FIELD] != SESSION_META_TYPE) return@scanHeaderLines false
            val payload = MetadataJsonParser.objectStringFields(text, PAYLOAD_FIELD, PAYLOAD_FIELDS)
            val projectPath = payload[WORKING_DIRECTORY_FIELD]?.takeIf { it.isNotBlank() } ?: return@scanHeaderLines false
            val sessionId = payload[ID_FIELD]
                ?.takeIf { it.isNotBlank() }
                ?: payload[LEGACY_SESSION_ID_FIELD]?.takeIf { it.isNotBlank() }
                ?: sessionIdFromFileName(sessionFile)
            val startedAt = LocalSessionSupport.parseTimestamp(payload[TIMESTAMP_FIELD] ?: topLevel[TIMESTAMP_FIELD])
            metadata = SessionMetadata(sessionId, projectPath, startedAt)
            metadata != null
        }
        val resolved = metadata ?: return null
        val lastEventAt = findLastEventTimestamp(sessionFile)
        val fileModifiedAt = runCatching { Files.getLastModifiedTime(sessionFile, LinkOption.NOFOLLOW_LINKS) }
            .getOrNull()
            ?.toInstant()
        val statistics = SessionStatisticsAccumulator(agentId)
        val messages = userMessages(sessionFile, statistics)
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = resolved.projectPath,
            sessionId = resolved.sessionId,
            startedAt = resolved.startedAt,
            updatedAt = latest(resolved.startedAt, lastEventAt, fileModifiedAt),
            sourcePath = sessionFile.toAbsolutePath().normalize().toString(),
            metadata = messages?.metadata().orEmpty(),
            statistics = statistics.snapshot(),
        )
    }

    /**
     * The user's prompts, from a full second pass. Current Codex records each one as an
     * `event_msg` → `item_completed` → `UserMessage` (older builds: `event_msg` → `user_message`).
     * The `response_item` `role:"user"` messages duplicate those but also carry injected context
     * (`<environment_context>`, subagent tasks), so they only count for rollouts that have no
     * dedicated user-message events at all.
     */
    private fun userMessages(sessionFile: Path, statistics: SessionStatisticsAccumulator): UserMessageTally? {
        val fallback = UserMessageTally()
        val tally = UserMessageTally.scanJsonl(sessionFile, USER_MARKERS) scan@{ line ->
            statistics.record(line)
            val topLevelType = MetadataJsonParser.topLevelStringFields(line, setOf(TYPE_FIELD))[TYPE_FIELD]
            val payloadType = MetadataJsonParser.stringAtPath(line, PAYLOAD_FIELD, TYPE_FIELD)
            when {
                topLevelType == EVENT_MSG_TYPE && payloadType == ITEM_COMPLETED_TYPE &&
                    MetadataJsonParser.stringAtPath(line, PAYLOAD_FIELD, ITEM_FIELD, TYPE_FIELD) == USER_MESSAGE_ITEM ->
                    {
                        val text = MessageContentExtractor.text(MetadataJsonParser.rawPath(line, PAYLOAD_FIELD, ITEM_FIELD, CONTENT_FIELD))
                        add(text)
                        statistics.userPrompt(text)
                    }
                topLevelType == EVENT_MSG_TYPE && payloadType == LEGACY_USER_MESSAGE_TYPE ->
                    {
                        val text = MetadataJsonParser.stringAtPath(line, PAYLOAD_FIELD, MESSAGE_FIELD)
                        add(text)
                        statistics.userPrompt(text)
                    }
                topLevelType == RESPONSE_ITEM_TYPE && payloadType == MESSAGE_FIELD &&
                    MetadataJsonParser.stringAtPath(line, PAYLOAD_FIELD, ROLE_FIELD) == USER_ROLE -> {
                    val text = MessageContentExtractor.text(MetadataJsonParser.rawPath(line, PAYLOAD_FIELD, CONTENT_FIELD))
                    if (text != null && !text.trimStart().startsWith("<")) fallback.add(text)
                }
            }
        } ?: return null
        return if (tally.count == 0) fallback else tally
    }

    private fun findLastEventTimestamp(sessionFile: Path): Instant? =
        LocalSessionSupport.lastTimestamp(sessionFile, MAX_TAIL_BYTES, MAX_TAIL_LINES) { line ->
            LocalSessionSupport.parseTimestamp(
                MetadataJsonParser.topLevelStringFields(line, setOf(TIMESTAMP_FIELD))[TIMESTAMP_FIELD],
            )
        }

    private fun sessionIdFromFileName(sessionFile: Path): String {
        val name = sessionFile.nameWithoutExtension
        return SESSION_ID_SUFFIX.find(name)?.groupValues?.get(1) ?: name
    }

    private data class SessionMetadata(
        val sessionId: String,
        val projectPath: String,
        val startedAt: Instant?,
    )

    companion object {
        private const val AGENT_ID = "codex"
        private const val SESSIONS_DIRECTORY = "sessions"
        private const val ARCHIVED_SESSIONS_DIRECTORY = "archived_sessions"
        private const val SESSION_INDEX_FILE = "session_index.jsonl"
        private const val THREAD_NAME_FIELD = "thread_name"
        private const val MAX_INDEX_BYTES = 8 * 1024 * 1024
        private const val MAX_INDEX_LINES = 50_000
        private const val MAX_TITLE_CHARACTERS = 300
        private val INDEX_FIELDS = setOf(ID_FIELD, THREAD_NAME_FIELD)
        private const val JSONL_EXTENSION = "jsonl"
        private const val MAX_SCAN_DEPTH = 4
        private const val MAX_SCAN_ENTRIES = 50_000
        private const val MAX_HEADER_LINES = 32
        private const val MAX_LINE_CHARACTERS = 256 * 1024
        private const val MAX_TAIL_BYTES = 256 * 1024
        private const val MAX_TAIL_LINES = 200
        private const val TYPE_FIELD = "type"
        private const val SESSION_META_TYPE = "session_meta"
        private const val PAYLOAD_FIELD = "payload"
        private const val ID_FIELD = "id"
        private const val LEGACY_SESSION_ID_FIELD = "session_id"
        private const val WORKING_DIRECTORY_FIELD = "cwd"
        private const val TIMESTAMP_FIELD = "timestamp"
        private val USER_MARKERS = listOf("\"timestamp\"", "\"turn_context\"", "\"token_count\"", "\"function_call\"", "\"custom_tool_call\"", "\"UserMessage\"", "\"user_message\"", "\"user\"")
        private const val EVENT_MSG_TYPE = "event_msg"
        private const val ITEM_COMPLETED_TYPE = "item_completed"
        private const val LEGACY_USER_MESSAGE_TYPE = "user_message"
        private const val USER_MESSAGE_ITEM = "UserMessage"
        private const val RESPONSE_ITEM_TYPE = "response_item"
        private const val ITEM_FIELD = "item"
        private const val MESSAGE_FIELD = "message"
        private const val CONTENT_FIELD = "content"
        private const val ROLE_FIELD = "role"
        private const val USER_ROLE = "user"
        private val TOP_LEVEL_FIELDS = setOf(TYPE_FIELD, TIMESTAMP_FIELD)
        private val PAYLOAD_FIELDS = setOf(
            ID_FIELD,
            LEGACY_SESSION_ID_FIELD,
            WORKING_DIRECTORY_FIELD,
            TIMESTAMP_FIELD,
        )
        private val SESSION_ID_SUFFIX = Regex(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$",
        )
        private val LOG = Logger.getLogger(CodexProjectProvider::class.java.name)

        private fun defaultCodexDirectory(): Path = EnvHomeDirectorySupport.resolve("CODEX_HOME", ".codex")
    }
}
