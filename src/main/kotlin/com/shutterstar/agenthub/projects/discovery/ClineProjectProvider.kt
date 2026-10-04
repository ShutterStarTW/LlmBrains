package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.projects.model.RawAgentProject
import com.shutterstar.agenthub.projects.resolve.ProcessCommandRunner
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.logging.Logger
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

data class ClineSessionRecord(
    val id: String,
    val directory: String,
    val startedAt: Instant?,
    val updatedAt: Instant?,
    val messagesPath: String? = null,
    val title: String? = null,
)

class ClineProjectProvider(
    private val dataDirectory: Path = defaultDataDirectory(),
    private val databaseReader: (Path) -> List<ClineSessionRecord> = ClineSqliteReader()::readSessions,
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val sessionsDirectory = dataDirectory.resolve(SESSIONS_DIRECTORY)

    override fun isAvailable(): Boolean =
        databaseFiles().isNotEmpty() || Files.isDirectory(sessionsDirectory, LinkOption.NOFOLLOW_LINKS)

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()

        val sessions = mutableListOf<RawAgentProject>()
        var databaseFailure: Throwable? = null
        databaseFiles().forEach { database ->
            runCatching { databaseReader(database) }
                .onSuccess { records -> sessions += records.map { it.toRawProject(database) } }
                .onFailure { databaseFailure = it }
        }
        sessions += discoverJsonSessions()
        if (sessions.isEmpty() && databaseFailure != null) {
            throw IOException("Cline session database could not be read", databaseFailure)
        }
        if (databaseFailure != null) {
            LOG.fine("[ProjectDiscovery] Cline: a database was skipped; alternate storage was used")
        }
        return LocalSessionSupport.deduplicate(sessions)
    }

    private fun databaseFiles(): List<Path> = DATABASE_PATHS
        .map(dataDirectory::resolve)
        .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        .distinct()

    private fun discoverJsonSessions(): List<RawAgentProject> {
        if (!Files.isDirectory(sessionsDirectory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return runCatching {
            Files.walk(sessionsDirectory, MAX_SCAN_DEPTH).use { paths ->
                paths
                    .limit(MAX_SCAN_ENTRIES.toLong())
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .filter { it.extension.equals(JSON_EXTENSION, ignoreCase = true) }
                    .sorted()
                    .map { runCatching { parseJsonSession(it) }.getOrNull() }
                    .filter { it != null }
                    .map { it!! }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun parseJsonSession(sessionFile: Path): RawAgentProject? {
        val json = LocalSessionSupport.readBoundedText(sessionFile, MAX_JSON_CHARACTERS) ?: return null
        val fields = MetadataJsonParser.topLevelStringFields(json, STRING_FIELDS)
        val longs = MetadataJsonParser.topLevelLongFields(json, TIME_FIELDS)
        val directory = fields[WORKSPACE_ROOT_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[CWD_FIELD]?.takeIf { it.isNotBlank() }
            ?: return null
        val sessionId = fields[SESSION_ID_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[ID_FIELD]?.takeIf { it.isNotBlank() }
            ?: sessionFile.nameWithoutExtension
        val startedAt = LocalSessionSupport.parseTimestamp(fields[STARTED_AT_FIELD])
            ?: LocalSessionSupport.epochTimestamp(longs[STARTED_AT_FIELD])
        val updatedAt = LocalSessionSupport.parseTimestamp(fields[UPDATED_AT_FIELD])
            ?: LocalSessionSupport.parseTimestamp(fields[ENDED_AT_FIELD])
            ?: LocalSessionSupport.epochTimestamp(longs[UPDATED_AT_FIELD] ?: longs[ENDED_AT_FIELD])
        val title = MetadataJsonParser.objectStringFields(json, METADATA_FIELD, setOf(TITLE_FIELD))[TITLE_FIELD]
        val messagesFile = fields[MESSAGES_PATH_FIELD]?.takeIf { it.isNotBlank() }?.let { runCatching { sessionFile.resolveSibling(it) }.getOrNull() }
            ?: sessionFile.resolveSibling("${sessionFile.nameWithoutExtension}$MESSAGES_FILE_SUFFIX")
        val statistics = SessionStatisticsAccumulator(agentId)
        val prompts = userMessages(messagesFile, statistics)
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = directory,
            sessionId = sessionId,
            startedAt = startedAt,
            updatedAt = LocalSessionSupport.latest(startedAt, updatedAt, LocalSessionSupport.modifiedAt(sessionFile)),
            sourcePath = sessionFile.toAbsolutePath().normalize().toString(),
            metadata = title?.takeIf { it.isNotBlank() }?.let { mapOf(TITLE_FIELD to it) }.orEmpty() +
                prompts?.metadata().orEmpty(),
            statistics = statistics.snapshot(),
        )
    }

    private fun ClineSessionRecord.toRawProject(database: Path): RawAgentProject {
        val statistics = SessionStatisticsAccumulator(agentId)
        val prompts = messagesPath?.let { runCatching { Path.of(it) }.getOrNull() }?.let { userMessages(it, statistics) }
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = directory,
            sessionId = id,
            startedAt = startedAt,
            updatedAt = LocalSessionSupport.latest(startedAt, updatedAt),
            sourcePath = database.toAbsolutePath().normalize().toString(),
            metadata = title?.takeIf { it.isNotBlank() }?.let { mapOf(TITLE_FIELD to it) }.orEmpty() +
                prompts?.metadata().orEmpty(),
            statistics = statistics.snapshot(),
        )
    }

    /**
     * `<session>.messages.json` holds `{"messages":[{"role":"user","content":[{"type":"text",...}]}]}`;
     * user-role messages that only carry tool results were not typed by the user.
     */
    private fun userMessages(messagesFile: Path, statistics: SessionStatisticsAccumulator): UserMessageTally? {
        if (!Files.isRegularFile(messagesFile, LinkOption.NOFOLLOW_LINKS)) return null
        val json = runCatching { LocalSessionSupport.readBoundedText(messagesFile, MAX_MESSAGES_CHARACTERS) }
            .getOrNull() ?: return null
        val messages = MetadataJsonParser.rawTopLevelField(json, MESSAGES_FIELD)
            ?.let(MetadataJsonParser::arrayElements) ?: return null
        return UserMessageTally().apply {
            messages.forEach { message ->
                statistics.record(message)
                if (MetadataJsonParser.topLevelStringFields(message, setOf(ROLE_FIELD))[ROLE_FIELD] != USER_ROLE) return@forEach
                val content = MetadataJsonParser.rawTopLevelField(message, CONTENT_FIELD)
                if (MessageContentExtractor.hasBlockOfType(content, TOOL_RESULT_TYPES)) return@forEach
                val text = MessageContentExtractor.text(content)
                add(text)
                statistics.userPrompt(text)
            }
        }
    }

    companion object {
        private const val AGENT_ID = "cline"
        private const val SESSIONS_DIRECTORY = "sessions"
        private const val JSON_EXTENSION = "json"
        private const val MAX_SCAN_DEPTH = 2
        private const val MAX_SCAN_ENTRIES = 20_000
        private const val MAX_JSON_CHARACTERS = 256 * 1024
        private const val SESSION_ID_FIELD = "session_id"
        private const val ID_FIELD = "id"
        private const val CWD_FIELD = "cwd"
        private const val WORKSPACE_ROOT_FIELD = "workspace_root"
        private const val STARTED_AT_FIELD = "started_at"
        private const val UPDATED_AT_FIELD = "updated_at"
        private const val ENDED_AT_FIELD = "ended_at"
        private const val METADATA_FIELD = "metadata"
        private const val TITLE_FIELD = "title"
        private const val MESSAGES_PATH_FIELD = "messages_path"
        private const val MESSAGES_FILE_SUFFIX = ".messages.json"
        private const val MESSAGES_FIELD = "messages"
        private const val ROLE_FIELD = "role"
        private const val USER_ROLE = "user"
        private const val CONTENT_FIELD = "content"
        private const val MAX_MESSAGES_CHARACTERS = 16 * 1024 * 1024
        private val TOOL_RESULT_TYPES = setOf("tool_result")
        private val DATABASE_PATHS = listOf(Path.of("db", "sessions.db"), Path.of("sessions", "sessions.db"))
        private val STRING_FIELDS = setOf(
            SESSION_ID_FIELD,
            ID_FIELD,
            CWD_FIELD,
            WORKSPACE_ROOT_FIELD,
            STARTED_AT_FIELD,
            UPDATED_AT_FIELD,
            ENDED_AT_FIELD,
            MESSAGES_PATH_FIELD,
        )
        private val TIME_FIELDS = setOf(STARTED_AT_FIELD, UPDATED_AT_FIELD, ENDED_AT_FIELD)
        private val LOG = Logger.getLogger(ClineProjectProvider::class.java.name)

        private fun defaultDataDirectory(): Path = EnvHomeDirectorySupport.resolve("CLINE_DATA_DIR", ".cline/data")
    }
}

class ClineSqliteReader(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val commandRunner: (List<String>, Long) -> String? = ProcessCommandRunner::run,
) {
    fun readSessions(database: Path): List<ClineSessionRecord> {
        val normalized = database.toAbsolutePath().normalize().toString()
        val output = SESSION_QUERIES.firstNotNullOfOrNull { query ->
            commandRunner(
                listOf(
                    "sqlite3",
                    "-readonly",
                    "-batch",
                    "-noheader",
                    "-separator",
                    "\t",
                    normalized,
                    query,
                ),
                timeoutMillis,
            )
        } ?: throw IOException("sqlite3 is unavailable or could not read the Cline database")
        return output.lineSequence().mapNotNull(::parseRow).toList()
    }

    private fun parseRow(line: String): ClineSessionRecord? {
        if (line.isBlank()) return null
        val columns = line.split('\t')
        if (columns.size !in EXPECTED_COLUMN_COUNTS) return null
        val id = decodeHex(columns[0])?.takeIf { it.isNotBlank() } ?: return null
        val directory = decodeHex(columns[1])?.takeIf { it.isNotBlank() } ?: return null
        return ClineSessionRecord(
            id = id,
            directory = directory,
            startedAt = LocalSessionSupport.parseTimestamp(decodeHex(columns[2])),
            updatedAt = LocalSessionSupport.parseTimestamp(decodeHex(columns[3])),
            messagesPath = columns.getOrNull(4)?.let(::decodeHex)?.takeIf { it.isNotBlank() },
            title = columns.getOrNull(5)?.let(::decodeHex)?.takeIf { it.isNotBlank() },
        )
    }

    private fun decodeHex(value: String): String? {
        if (value.length % 2 != 0) return null
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            bytes[index] = (value.substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null).toByte()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        private val EXPECTED_COLUMN_COUNTS = setOf(4, 5, 6)
        private val SESSION_QUERIES = listOf(
            "SELECT hex(session_id), hex(COALESCE(NULLIF(workspace_root, ''), cwd)), " +
                "hex(started_at), hex(COALESCE(NULLIF(updated_at, ''), ended_at, started_at)), " +
                "hex(COALESCE(messages_path, '')), " +
                "hex(CASE WHEN json_valid(metadata_json) THEN COALESCE(json_extract(metadata_json, '$.title'), '') ELSE '' END) " +
                "FROM sessions WHERE COALESCE(is_subagent, 0) = 0 " +
                "ORDER BY COALESCE(NULLIF(updated_at, ''), ended_at, started_at) DESC LIMIT 20000;",
            "SELECT hex(session_id), hex(COALESCE(NULLIF(workspace_root, ''), cwd)), " +
                "hex(started_at), hex(COALESCE(NULLIF(updated_at, ''), ended_at, started_at)), " +
                "hex(COALESCE(messages_path, '')) " +
                "FROM sessions WHERE COALESCE(is_subagent, 0) = 0 " +
                "ORDER BY COALESCE(NULLIF(updated_at, ''), ended_at, started_at) DESC LIMIT 20000;",
            // Same without messages_path, for databases that predate that column.
            "SELECT hex(session_id), hex(COALESCE(NULLIF(workspace_root, ''), cwd)), " +
                "hex(started_at), hex(COALESCE(NULLIF(updated_at, ''), ended_at, started_at)) " +
                "FROM sessions WHERE COALESCE(is_subagent, 0) = 0 " +
                "ORDER BY COALESCE(NULLIF(updated_at, ''), ended_at, started_at) DESC LIMIT 20000;",
            "SELECT hex(session_id), hex(cwd), hex(started_at), " +
                "hex(COALESCE(ended_at, started_at)) FROM sessions " +
                "ORDER BY COALESCE(ended_at, started_at) DESC LIMIT 20000;",
        )
    }
}
