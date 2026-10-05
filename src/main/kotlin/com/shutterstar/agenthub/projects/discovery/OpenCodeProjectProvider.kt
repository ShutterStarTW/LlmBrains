package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.discovery.MimoHomeSupport
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.latest
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.readBoundedText
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

data class OpenCodeSessionRecord(
    val id: String,
    val directory: String,
    val title: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val userMessageCount: Int? = null,
    val firstMessage: String? = null,
    val statistics: Map<String, String> = emptyMap(),
)

/** What differs between OpenCode and its forks: ids, data directory name, database file names, legacy JSON storage. */
data class OpenCodeStorageFlavor(
    val agentId: String,
    /** The `$XDG_DATA_HOME/<appName>` directory name. */
    val appName: String,
    val databaseName: String,
    /** Channel databases: `<prefix><channel>.db` next to the stable one. */
    val databasePrefix: String,
    /** The pre-SQLite `storage/…` JSON layout (OpenCode only; Kilo never had it). */
    val hasLegacyJsonStorage: Boolean,
) {
    companion object {
        val OPENCODE = OpenCodeStorageFlavor("opencode", "opencode", "opencode.db", "opencode-", true)
        val KILO = OpenCodeStorageFlavor("kilo", "kilo", "kilo.db", "kilo-", false)
        val MIMO = OpenCodeStorageFlavor("mimo", "mimocode", "mimocode.db", "mimocode-", false)
    }
}

/**
 * Session discovery shared by OpenCode and its SQLite-compatible forks (Kilo): the same
 * `session`/`message`/`part` schema, differing only in [flavor]. Use [OpenCodeProjectProvider],
 * [KiloProjectProvider] or [MimoProjectProvider].
 */
open class OpenCodeFamilyProjectProvider(
    private val flavor: OpenCodeStorageFlavor,
    private val dataDirectory: Path,
    private val maxLegacyScanEntries: Int = MAX_LEGACY_SCAN_ENTRIES,
    private val databaseReader: (Path) -> List<OpenCodeSessionRecord> = OpenCodeSqliteReader(flavor.agentId)::readSessions,
) : AgentProjectProvider {
    override val agentId: String = flavor.agentId

    private val legacyDirectories = if (flavor.hasLegacyJsonStorage) {
        listOf(
            dataDirectory.resolve(STORAGE_DIRECTORY).resolve(SESSION_DIRECTORY),
            dataDirectory.resolve(PROJECT_DIRECTORY),
        )
    } else {
        emptyList()
    }

    override fun isAvailable(): Boolean =
        legacyDirectories.any { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) } || databaseFiles().isNotEmpty()

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()

        val sessions = mutableListOf<RawAgentProject>()
        var databaseFailure: Throwable? = null
        databaseFiles().forEach { database ->
            runCatching { databaseReader(database) }
                .onSuccess { records -> sessions += records.map { it.toRawProject(database) } }
                .onFailure { error -> databaseFailure = error }
        }
        sessions += discoverLegacySessions()
        if (sessions.isEmpty() && databaseFailure != null) {
            throw IOException("${flavor.appName} session database could not be read", databaseFailure)
        }
        if (databaseFailure != null) {
            LOG.fine("[ProjectDiscovery] ${flavor.appName}: a database was skipped; legacy or alternate storage was used")
        }
        return LocalSessionSupport.deduplicate(sessions)
    }

    private fun databaseFiles(): List<Path> {
        if (!Files.isDirectory(dataDirectory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return runCatching {
            Files.list(dataDirectory).use { files ->
                files
                    .limit(MAX_DATABASE_FILES.toLong())
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .filter { file ->
                        val name = file.fileName.toString()
                        name == flavor.databaseName || (name.startsWith(flavor.databasePrefix) && name.endsWith(DATABASE_SUFFIX))
                    }
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun discoverLegacySessions(): List<RawAgentProject> {
        var remainingEntries = maxLegacyScanEntries.coerceAtLeast(0)
        val sessions = mutableListOf<RawAgentProject>()
        legacyDirectories.forEach { directory ->
            if (remainingEntries == 0 || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return@forEach
            val visited = mutableListOf<Path>()
            runCatching {
                Files.walk(directory, MAX_LEGACY_SCAN_DEPTH).use { paths ->
                    paths.skip(1).limit(remainingEntries.toLong()).forEach(visited::add)
                }
            }
            remainingEntries -= visited.size
            sessions += visited.asSequence()
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { it.extension.equals(JSON_EXTENSION, ignoreCase = true) }
                .mapNotNull { file -> runCatching { parseLegacySession(file) }.getOrNull() }
                .toList()
        }
        return sessions
    }

    private fun parseLegacySession(sessionFile: Path): RawAgentProject? {
        val json = readBoundedText(sessionFile, MAX_LEGACY_FILE_CHARACTERS) ?: return null
        val fields = MetadataJsonParser.topLevelStringFields(json, LEGACY_STRING_FIELDS)
        val directory = fields[DIRECTORY_FIELD]?.takeIf { it.isNotBlank() } ?: return null
        val sessionId = fields[ID_FIELD]?.takeIf { it.isNotBlank() } ?: sessionFile.nameWithoutExtension
        val timestamps = MetadataJsonParser.objectLongFields(json, TIME_FIELD, LEGACY_TIME_FIELDS)
        val createdAt = epochMillis(timestamps[CREATED_FIELD])
        val updatedAt = epochMillis(timestamps[UPDATED_FIELD])
        val fileModifiedAt = runCatching { Files.getLastModifiedTime(sessionFile, LinkOption.NOFOLLOW_LINKS) }
            .getOrNull()
            ?.toInstant()
        val statistics = SessionStatisticsAccumulator(agentId)
        val messageMetadata = legacyMessageMetadata(sessionId, statistics)
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = directory,
            sessionId = sessionId,
            startedAt = createdAt,
            updatedAt = latest(createdAt, updatedAt, fileModifiedAt),
            sourcePath = sessionFile.toAbsolutePath().normalize().toString(),
            metadata = buildMap {
                fields[TITLE_FIELD]?.let { put("title", it) }
                putAll(messageMetadata)
            },
            statistics = statistics.snapshot(),
        )
    }

    /** Legacy OpenCode keeps message metadata and text parts in separate, session-scoped folders. */
    private fun legacyMessageMetadata(sessionId: String, statistics: SessionStatisticsAccumulator): Map<String, String> {
        if (!sessionId.startsWith("ses_") || '/' in sessionId || '\\' in sessionId) return emptyMap()
        val messageDirectory = dataDirectory.resolve(STORAGE_DIRECTORY).resolve(MESSAGE_DIRECTORY).resolve(sessionId)
        if (!Files.isDirectory(messageDirectory, LinkOption.NOFOLLOW_LINKS)) return emptyMap()
        val tally = UserMessageTally()
        val messages = runCatching {
            Files.list(messageDirectory).use { files ->
                files.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.extension == JSON_EXTENSION }
                    .sorted()
                    .limit(MAX_LEGACY_MESSAGES_PER_SESSION.toLong())
                    .toList()
            }
        }.getOrNull() ?: return emptyMap()
        messages.forEach { messageFile ->
            val json = readBoundedText(messageFile, MAX_LEGACY_MESSAGE_CHARACTERS) ?: return@forEach
            val fields = MetadataJsonParser.topLevelStringFields(json, LEGACY_MESSAGE_FIELDS)
            if (fields[SESSION_ID_FIELD] == sessionId) {
                statistics.record(json)
                if (fields[ROLE_FIELD] == USER_ROLE) statistics.userPrompt()
            }
            if (fields[ROLE_FIELD] != USER_ROLE || fields[SESSION_ID_FIELD] != sessionId) return@forEach
            val messageId = fields[ID_FIELD]?.takeIf { it.startsWith("msg_") && '/' !in it && '\\' !in it }
            tally.add(if (tally.firstMessage == null) messageId?.let(::legacyFirstTextPart) else null)
        }
        return tally.metadata()
    }

    private fun legacyFirstTextPart(messageId: String): String? {
        val partDirectory = dataDirectory.resolve(STORAGE_DIRECTORY).resolve(PART_DIRECTORY).resolve(messageId)
        if (!Files.isDirectory(partDirectory, LinkOption.NOFOLLOW_LINKS)) return null
        return runCatching {
            Files.list(partDirectory).use { files ->
                files.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.extension == JSON_EXTENSION }
                    .sorted()
                    .limit(MAX_LEGACY_PARTS_PER_MESSAGE.toLong())
                    .map { partFile -> readBoundedText(partFile, MAX_LEGACY_MESSAGE_CHARACTERS) }
                    .filter { it != null }
                    .map { it!! }
                    .filter { part ->
                        val fields = MetadataJsonParser.topLevelStringFields(part, LEGACY_PART_FIELDS)
                        fields[TYPE_FIELD] == TEXT_TYPE && fields[MESSAGE_ID_FIELD] == messageId &&
                            MetadataJsonParser.topLevelBooleanFields(part, setOf(SYNTHETIC_FIELD))[SYNTHETIC_FIELD] != true
                    }
                    .map { part -> MetadataJsonParser.topLevelStringFields(part, setOf(TEXT_FIELD))[TEXT_FIELD] }
                    .filter { !it.isNullOrBlank() && !MessageContentExtractor.isInjectedContext(it) }
                    .findFirst()
                    .orElse(null)
            }
        }.getOrNull()
    }

    private fun OpenCodeSessionRecord.toRawProject(database: Path) = RawAgentProject(
        agentId = agentId,
        rawProjectPath = directory,
        sessionId = id,
        startedAt = createdAt,
        updatedAt = latest(createdAt, updatedAt),
        sourcePath = database.toAbsolutePath().normalize().toString(),
        metadata = buildMap {
            title?.let { put("title", it) }
            userMessageCount?.let { count ->
                put(UserMessageTally.MESSAGE_COUNT_KEY, count.toString())
                MessageContentExtractor.titleText(firstMessage)?.let { put(UserMessageTally.FIRST_MESSAGE_KEY, it) }
            }
        },
        statistics = statistics,
    )

    private fun epochMillis(value: Long?): Instant? = value?.let {
        runCatching { Instant.ofEpochMilli(it) }.getOrNull()
    }

    companion object {
        private const val STORAGE_DIRECTORY = "storage"
        private const val SESSION_DIRECTORY = "session"
        private const val MESSAGE_DIRECTORY = "message"
        private const val PART_DIRECTORY = "part"
        private const val PROJECT_DIRECTORY = "project"
        private const val DATABASE_SUFFIX = ".db"
        private const val JSON_EXTENSION = "json"
        private const val MAX_DATABASE_FILES = 100
        private const val MAX_LEGACY_SCAN_DEPTH = 8
        private const val MAX_LEGACY_SCAN_ENTRIES = 50_000
        private const val MAX_LEGACY_FILE_CHARACTERS = 256 * 1024
        private const val MAX_LEGACY_MESSAGE_CHARACTERS = 32 * 1024
        private const val MAX_LEGACY_MESSAGES_PER_SESSION = 2_000
        private const val MAX_LEGACY_PARTS_PER_MESSAGE = 100
        private const val ID_FIELD = "id"
        private const val DIRECTORY_FIELD = "directory"
        private const val TITLE_FIELD = "title"
        private const val ROLE_FIELD = "role"
        private const val USER_ROLE = "user"
        private const val SESSION_ID_FIELD = "sessionID"
        private const val MESSAGE_ID_FIELD = "messageID"
        private const val TYPE_FIELD = "type"
        private const val TEXT_TYPE = "text"
        private const val TEXT_FIELD = "text"
        private const val SYNTHETIC_FIELD = "synthetic"
        private const val TIME_FIELD = "time"
        private const val CREATED_FIELD = "created"
        private const val UPDATED_FIELD = "updated"
        private val LEGACY_STRING_FIELDS = setOf(ID_FIELD, DIRECTORY_FIELD, TITLE_FIELD)
        private val LEGACY_MESSAGE_FIELDS = setOf(ID_FIELD, SESSION_ID_FIELD, ROLE_FIELD)
        private val LEGACY_PART_FIELDS = setOf(TYPE_FIELD, MESSAGE_ID_FIELD)
        private val LEGACY_TIME_FIELDS = setOf(CREATED_FIELD, UPDATED_FIELD)
        private val LOG = Logger.getLogger(OpenCodeFamilyProjectProvider::class.java.name)
        internal const val MAX_LEGACY_SCAN_ENTRIES_DEFAULT = MAX_LEGACY_SCAN_ENTRIES

        internal fun defaultDataDirectory(flavor: OpenCodeStorageFlavor): Path = EnvHomeDirectorySupport.resolveXdgGuarded(
            "XDG_DATA_HOME", Path.of(System.getProperty("user.home")), ".local/share", flavor.appName,
        )
    }
}

class OpenCodeProjectProvider(
    dataDirectory: Path = OpenCodeFamilyProjectProvider.defaultDataDirectory(OpenCodeStorageFlavor.OPENCODE),
    maxLegacyScanEntries: Int = OpenCodeFamilyProjectProvider.MAX_LEGACY_SCAN_ENTRIES_DEFAULT,
    databaseReader: (Path) -> List<OpenCodeSessionRecord> = OpenCodeSqliteReader(OpenCodeStorageFlavor.OPENCODE.agentId)::readSessions,
) : OpenCodeFamilyProjectProvider(OpenCodeStorageFlavor.OPENCODE, dataDirectory, maxLegacyScanEntries, databaseReader)

/** Kilo Code CLI: an OpenCode fork with the same SQLite schema in `$XDG_DATA_HOME/kilo/kilo.db`. */
class KiloProjectProvider(
    dataDirectory: Path = OpenCodeFamilyProjectProvider.defaultDataDirectory(OpenCodeStorageFlavor.KILO),
    databaseReader: (Path) -> List<OpenCodeSessionRecord> = OpenCodeSqliteReader(OpenCodeStorageFlavor.KILO.agentId)::readSessions,
) : OpenCodeFamilyProjectProvider(OpenCodeStorageFlavor.KILO, dataDirectory, OpenCodeFamilyProjectProvider.MAX_LEGACY_SCAN_ENTRIES_DEFAULT, databaseReader)

/** MiMo Code CLI (Xiaomi): an OpenCode fork with the same SQLite schema in `mimocode.db` of its data directory (`MIMOCODE_HOME/data`, or `$XDG_DATA_HOME/mimocode`). */
class MimoProjectProvider(
    dataDirectory: Path = MimoHomeSupport.dataDirectory(Path.of(System.getProperty("user.home"))),
    databaseReader: (Path) -> List<OpenCodeSessionRecord> = OpenCodeSqliteReader(OpenCodeStorageFlavor.MIMO.agentId)::readSessions,
) : OpenCodeFamilyProjectProvider(OpenCodeStorageFlavor.MIMO, dataDirectory, OpenCodeFamilyProjectProvider.MAX_LEGACY_SCAN_ENTRIES_DEFAULT, databaseReader)

class OpenCodeSqliteReader(
    private val statisticsAgentId: String = "opencode",
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val commandRunner: (List<String>, Long) -> String? = ProcessCommandRunner::run,
) {
    fun readSessions(database: Path): List<OpenCodeSessionRecord> {
        val output = query(database, SESSION_QUERY)
            ?: throw IOException("sqlite3 is unavailable or could not read the database")
        val sessions = output.lineSequence().mapNotNull(::parseRow).toList()
        val stats = query(database, USER_MESSAGE_QUERY)?.lineSequence()?.mapNotNull(::parseStatsRow)?.toMap().orEmpty()
        val usage = linkedMapOf<String, SessionStatisticsAccumulator>()
        (query(database, USAGE_QUERY) ?: query(database, MESSAGE_USAGE_QUERY))?.lineSequence()?.forEach { line ->
            val columns = line.split('\t')
            if (columns.size != 2) return@forEach
            val sessionId = decodeHex(columns[0]) ?: return@forEach
            val record = decodeHex(columns[1]) ?: return@forEach
            val accumulator = usage.getOrPut(sessionId) { SessionStatisticsAccumulator(statisticsAgentId) }
            accumulator.record(record)
            if (MetadataJsonParser.topLevelStringFields(record, setOf("role"))["role"] == "user") accumulator.userPrompt()
        }
        return sessions.map { session ->
            val user = stats[session.id]
            usage[session.id]?.recordedBounds(session.createdAt, session.updatedAt)
            session.copy(userMessageCount = user?.first ?: if (stats.isEmpty()) null else 0, firstMessage = user?.second,
                statistics = usage[session.id]?.snapshot().orEmpty())
        }
    }

    private fun query(database: Path, sql: String): String? = commandRunner(
        listOf(
            "sqlite3",
            "-readonly",
            "-batch",
            "-noheader",
            "-separator",
            "\t",
            database.toAbsolutePath().normalize().toString(),
            sql,
        ),
        timeoutMillis,
    )

    private fun parseStatsRow(line: String): Pair<String, Pair<Int, String?>>? {
        val columns = line.split('\t')
        if (columns.size != STATS_COLUMN_COUNT) return null
        val id = decodeHex(columns[0])?.takeIf { it.isNotBlank() } ?: return null
        val count = columns[1].trim().toIntOrNull() ?: return null
        return id to (count to decodeHex(columns[2])?.takeIf { it.isNotBlank() })
    }

    private fun parseRow(line: String): OpenCodeSessionRecord? {
        if (line.isBlank()) return null
        val columns = line.split('\t')
        if (columns.size != EXPECTED_COLUMN_COUNT) return null
        val id = decodeHex(columns[0])?.takeIf { it.isNotBlank() } ?: return null
        val directory = decodeHex(columns[1])?.takeIf { it.isNotBlank() } ?: return null
        val title = decodeHex(columns[2])?.takeIf { it.isNotBlank() }
        return OpenCodeSessionRecord(
            id = id,
            directory = directory,
            title = title,
            createdAt = epochMillis(columns[3]),
            updatedAt = epochMillis(columns[4]),
        )
    }

    private fun decodeHex(value: String): String? {
        if (value.length % 2 != 0) return null
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val byte = value.substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null
            bytes[index] = byte.toByte()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun epochMillis(value: String): Instant? = value.toLongOrNull()?.let {
        runCatching { Instant.ofEpochMilli(it) }.getOrNull()
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        private const val EXPECTED_COLUMN_COUNT = 5
        private const val SESSION_QUERY =
            "SELECT hex(id), hex(directory), hex(title), time_created, time_updated " +
                "FROM session ORDER BY time_updated DESC LIMIT 20000;"
        private const val STATS_COLUMN_COUNT = 3
        private const val MESSAGE_USAGE_QUERY =
            "SELECT hex(session_id), hex(json_object('id',id,'role',json_extract(data,'$.role')," +
                "'modelID',json_extract(data,'$.modelID'),'time',json_extract(data,'$.time')," +
                "'tokens',json_extract(data,'$.tokens'),'cost',json_extract(data,'$.cost'))) " +
                "FROM message WHERE json_valid(data) ORDER BY time_created,id LIMIT 200000;"
        // Select only usage metadata; message content, credentials and tool arguments never leave SQLite.
        private const val USAGE_QUERY =
            "SELECT hex(session_id), hex(record) FROM (" +
                "SELECT session_id,time_created,id,json_object('id',id,'role',json_extract(data,'$.role')," +
                "'modelID',json_extract(data,'$.modelID'),'time',json_extract(data,'$.time')," +
                "'tokens',json_extract(data,'$.tokens'),'cost',json_extract(data,'$.cost')) AS record " +
                "FROM message WHERE json_valid(data) UNION ALL " +
                "SELECT m.session_id,p.time_created,p.id,json_object('id',p.id,'type','tool'," +
                "'tool',json_extract(p.data,'$.tool'),'callID',json_extract(p.data,'$.callID')," +
                "'state',json_object('status',json_extract(p.data,'$.state.status'))) " +
                "FROM part p JOIN message m ON m.id=p.message_id WHERE json_valid(p.data) " +
                "AND json_extract(p.data,'$.type')='tool') ORDER BY time_created,id LIMIT 200000;"

        /**
         * Per session: the number of user-role messages and the first non-synthetic text part the
         * user wrote. Needs SQLite's JSON functions; when they are missing only this query fails.
         */
        private const val USER_MESSAGE_QUERY =
            "SELECT hex(m.session_id), COUNT(*), hex(COALESCE((" +
                "SELECT substr(json_extract(p.data, '$.text'), 1, 2000) FROM part p " +
                "JOIN message m2 ON p.message_id = m2.id " +
                "WHERE m2.session_id = m.session_id AND json_extract(m2.data, '$.role') = 'user' " +
                "AND json_extract(p.data, '$.type') = 'text' " +
                "AND COALESCE(json_extract(p.data, '$.synthetic'), 0) = 0 " +
                "ORDER BY m2.time_created, p.time_created LIMIT 1), '')) " +
                "FROM message m WHERE json_extract(m.data, '$.role') = 'user' GROUP BY m.session_id;"
    }
}
