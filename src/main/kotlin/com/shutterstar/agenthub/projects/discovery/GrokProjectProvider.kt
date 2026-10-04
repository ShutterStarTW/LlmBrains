package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.ScanBudget
import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.io.path.name

/**
 * Discovers Grok Build sessions from `summary.json` metadata, plus the user's prompts from
 * `chat_history.jsonl`.
 *
 * Layout: `~/.grok/sessions/<percent-encoded-cwd>/<session-id>/{summary.json,chat_history.jsonl}`
 */
class GrokProjectProvider(
    private val grokDirectory: Path = defaultGrokDirectory(),
    private val maxScanEntries: Int = MAX_SCAN_ENTRIES,
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val sessionsDirectory = grokDirectory.resolve(SESSIONS_DIRECTORY)

    override fun isAvailable(): Boolean = Files.isDirectory(sessionsDirectory, LinkOption.NOFOLLOW_LINKS)

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()
        var skippedSessions = 0
        val budget = ScanBudget(maxScanEntries)
        val sessions = runCatching {
            LocalSessionSupport.listDirectories(sessionsDirectory, MAX_WORKSPACE_ENTRIES).flatMap { workspaceDirectory ->
                LocalSessionSupport.listDirectories(workspaceDirectory, budget).mapNotNull { sessionDirectory ->
                    runCatching { parseSession(sessionDirectory, workspaceDirectory) }
                        .getOrNull()
                        .also { if (it == null) skippedSessions++ }
                }
            }
        }.getOrDefault(emptyList())
        if (skippedSessions > 0) {
            LOG.fine("[ProjectDiscovery] Grok: skipped $skippedSessions malformed or unreadable sessions")
        }
        return LocalSessionSupport.deduplicate(sessions)
    }

    private fun parseSession(sessionDirectory: Path, workspaceDirectory: Path): RawAgentProject? {
        val summaryFile = sessionDirectory.resolve(SUMMARY_FILE)
        val json = if (Files.isRegularFile(summaryFile, LinkOption.NOFOLLOW_LINKS)) {
            LocalSessionSupport.readBoundedText(summaryFile, MAX_JSON_CHARACTERS)
        } else {
            null
        }
        val fields = json?.let { MetadataJsonParser.topLevelStringFields(it, STRING_FIELDS) }.orEmpty()
        val info = json?.let { MetadataJsonParser.objectStringFields(it, INFO_FIELD, INFO_FIELDS) }.orEmpty()

        val projectPath = info[CWD_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[GIT_ROOT_FIELD]?.takeIf { it.isNotBlank() }
            ?: percentDecode(workspaceDirectory.name)?.takeIf { it.isNotBlank() }
            ?: return null
        val sessionId = info[ID_FIELD]?.takeIf { it.isNotBlank() }
            ?: sessionDirectory.name.takeIf { it.isNotBlank() }
            ?: return null
        val startedAt = LocalSessionSupport.parseTimestamp(fields[CREATED_AT_FIELD])
        val recordedUpdate = LocalSessionSupport.latest(
            LocalSessionSupport.parseTimestamp(fields[LAST_ACTIVE_AT_FIELD]),
            LocalSessionSupport.parseTimestamp(fields[UPDATED_AT_FIELD]),
        )
        val sourceFile = summaryFile.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) } ?: sessionDirectory
        val updatedAt = LocalSessionSupport.latest(
            startedAt,
            recordedUpdate,
            LocalSessionSupport.modifiedAt(sourceFile),
        )
        val title = fields[GENERATED_TITLE_FIELD]?.takeIf { it.isNotBlank() }
        val statistics = SessionStatisticsAccumulator(agentId)
        val updates = UserMessageTally.scanJsonl(sessionDirectory.resolve("updates.jsonl"), listOf("\"params\"")) { line -> statistics.record(line) }
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = projectPath,
            sessionId = sessionId,
            startedAt = startedAt,
            updatedAt = updatedAt,
            sourcePath = sourceFile.toAbsolutePath().normalize().toString(),
            metadata = title?.let { mapOf(TITLE_FIELD to it) }.orEmpty() +
                userMessages(sessionDirectory.resolve(CHAT_HISTORY_FILE))?.metadata().orEmpty(),
            statistics = if (updates != null) statistics.snapshot() else emptyMap(),
        )
    }

    /**
     * `type:"user"` records, except what Grok injects itself: records with a `synthetic_reason`
     * and context blocks such as `<user_info>` (the typed prompt comes wrapped in `<user_query>`).
     */
    private fun userMessages(chatHistory: Path): UserMessageTally? =
        UserMessageTally.scanJsonl(chatHistory, USER_MARKERS) { line ->
            val fields = MetadataJsonParser.topLevelStringFields(line, CHAT_FIELDS)
            if (fields[TYPE_FIELD] != USER_TYPE || fields[SYNTHETIC_REASON_FIELD] != null) return@scanJsonl
            val text = MessageContentExtractor.text(MetadataJsonParser.rawTopLevelField(line, CONTENT_FIELD))
            if (!MessageContentExtractor.isInjectedContext(text)) add(text)
        }

    companion object {
        private const val AGENT_ID = "grok"
        private const val GROK_DIRECTORY = ".grok"
        private const val SESSIONS_DIRECTORY = "sessions"
        private const val SUMMARY_FILE = "summary.json"
        private const val CHAT_HISTORY_FILE = "chat_history.jsonl"
        private const val TYPE_FIELD = "type"
        private const val USER_TYPE = "user"
        private const val SYNTHETIC_REASON_FIELD = "synthetic_reason"
        private const val CONTENT_FIELD = "content"
        private val CHAT_FIELDS = setOf(TYPE_FIELD, SYNTHETIC_REASON_FIELD)
        private val USER_MARKERS = listOf("\"user\"")
        private const val MAX_SCAN_ENTRIES = 20_000
        private const val MAX_WORKSPACE_ENTRIES = 4_096
        private const val MAX_JSON_CHARACTERS = 256 * 1024
        private const val INFO_FIELD = "info"
        private const val ID_FIELD = "id"
        private const val CWD_FIELD = "cwd"
        private const val CREATED_AT_FIELD = "created_at"
        private const val UPDATED_AT_FIELD = "updated_at"
        private const val LAST_ACTIVE_AT_FIELD = "last_active_at"
        private const val GENERATED_TITLE_FIELD = "generated_title"
        private const val GIT_ROOT_FIELD = "git_root_dir"
        private const val TITLE_FIELD = "title"
        private val INFO_FIELDS = setOf(ID_FIELD, CWD_FIELD)
        private val STRING_FIELDS = setOf(
            CREATED_AT_FIELD,
            UPDATED_AT_FIELD,
            LAST_ACTIVE_AT_FIELD,
            GENERATED_TITLE_FIELD,
            GIT_ROOT_FIELD,
        )
        private val LOG = Logger.getLogger(GrokProjectProvider::class.java.name)

        fun defaultGrokDirectory(): Path = EnvHomeDirectorySupport.resolve("GROK_HOME", GROK_DIRECTORY)

        internal fun percentDecode(value: String): String? {
            if (value.isEmpty()) return null
            return runCatching {
                val bytes = ArrayList<Byte>(value.length)
                var index = 0
                while (index < value.length) {
                    val character = value[index]
                    if (character == '%' && index + 2 < value.length) {
                        val decoded = value.substring(index + 1, index + 3).toInt(16)
                        bytes.add(decoded.toByte())
                        index += 3
                    } else {
                        bytes.add(character.code.toByte())
                        index++
                    }
                }
                String(bytes.toByteArray(), StandardCharsets.UTF_8)
            }.getOrNull()
        }
    }
}
