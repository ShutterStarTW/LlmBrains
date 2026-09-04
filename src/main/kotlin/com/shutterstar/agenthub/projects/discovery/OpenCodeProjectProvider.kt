package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.latest
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.readBoundedText
import com.shutterstar.agenthub.projects.model.ProjectComparators
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
)

class OpenCodeProjectProvider(
    private val dataDirectory: Path = defaultDataDirectory(),
    private val maxLegacyScanEntries: Int = MAX_LEGACY_SCAN_ENTRIES,
    private val databaseReader: (Path) -> List<OpenCodeSessionRecord> = OpenCodeSqliteReader()::readSessions,
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val legacyDirectories = listOf(
        dataDirectory.resolve(STORAGE_DIRECTORY).resolve(SESSION_DIRECTORY),
        dataDirectory.resolve(PROJECT_DIRECTORY),
    )

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
            throw IOException("OpenCode session database could not be read", databaseFailure)
        }
        if (databaseFailure != null) {
            LOG.fine("[ProjectDiscovery] OpenCode: a database was skipped; legacy or alternate storage was used")
        }
        return deduplicate(sessions)
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
                        name == DATABASE_NAME || (name.startsWith(DATABASE_PREFIX) && name.endsWith(DATABASE_SUFFIX))
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
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = directory,
            sessionId = sessionId,
            startedAt = createdAt,
            updatedAt = latest(createdAt, updatedAt, fileModifiedAt),
            sourcePath = sessionFile.toAbsolutePath().normalize().toString(),
            metadata = fields[TITLE_FIELD]?.let { mapOf("title" to it) }.orEmpty(),
        )
    }

    private fun OpenCodeSessionRecord.toRawProject(database: Path) = RawAgentProject(
        agentId = agentId,
        rawProjectPath = directory,
        sessionId = id,
        startedAt = createdAt,
        updatedAt = latest(createdAt, updatedAt),
        sourcePath = database.toAbsolutePath().normalize().toString(),
        metadata = title?.let { mapOf("title" to it) }.orEmpty(),
    )

    private fun deduplicate(sessions: List<RawAgentProject>): List<RawAgentProject> {
        val bySessionId = linkedMapOf<String, RawAgentProject>()
        sessions.forEach { candidate ->
            val existing = bySessionId[candidate.sessionId]
            val candidateActivity = candidate.updatedAt ?: candidate.startedAt ?: Instant.MIN
            val existingActivity = existing?.updatedAt ?: existing?.startedAt ?: Instant.MIN
            if (existing == null || candidateActivity > existingActivity) {
                bySessionId[candidate.sessionId] = candidate
            }
        }
        return bySessionId.values.sortedWith(ProjectComparators.rawAgentProjectByRecency)
    }

    private fun epochMillis(value: Long?): Instant? = value?.let {
        runCatching { Instant.ofEpochMilli(it) }.getOrNull()
    }

    companion object {
        private const val AGENT_ID = "opencode"
        private const val STORAGE_DIRECTORY = "storage"
        private const val SESSION_DIRECTORY = "session"
        private const val PROJECT_DIRECTORY = "project"
        private const val DATABASE_NAME = "opencode.db"
        private const val DATABASE_PREFIX = "opencode-"
        private const val DATABASE_SUFFIX = ".db"
        private const val JSON_EXTENSION = "json"
        private const val MAX_DATABASE_FILES = 100
        private const val MAX_LEGACY_SCAN_DEPTH = 8
        private const val MAX_LEGACY_SCAN_ENTRIES = 50_000
        private const val MAX_LEGACY_FILE_CHARACTERS = 256 * 1024
        private const val ID_FIELD = "id"
        private const val DIRECTORY_FIELD = "directory"
        private const val TITLE_FIELD = "title"
        private const val TIME_FIELD = "time"
        private const val CREATED_FIELD = "created"
        private const val UPDATED_FIELD = "updated"
        private val LEGACY_STRING_FIELDS = setOf(ID_FIELD, DIRECTORY_FIELD, TITLE_FIELD)
        private val LEGACY_TIME_FIELDS = setOf(CREATED_FIELD, UPDATED_FIELD)
        private val LOG = Logger.getLogger(OpenCodeProjectProvider::class.java.name)

        private fun defaultDataDirectory(): Path {
            val xdgDataHome = System.getenv("XDG_DATA_HOME")?.trim()?.takeIf { it.isNotEmpty() }
            return xdgDataHome?.let { runCatching { Path.of(it).resolve("opencode") }.getOrNull() }
                ?: Path.of(System.getProperty("user.home"), ".local", "share", "opencode")
        }
    }
}

class OpenCodeSqliteReader(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val commandRunner: (List<String>, Long) -> String? = ProcessCommandRunner::run,
) {
    fun readSessions(database: Path): List<OpenCodeSessionRecord> {
        val output = commandRunner(
            listOf(
                "sqlite3",
                "-readonly",
                "-batch",
                "-noheader",
                "-separator",
                "\t",
                database.toAbsolutePath().normalize().toString(),
                SESSION_QUERY,
            ),
            timeoutMillis,
        ) ?: throw IOException("sqlite3 is unavailable or could not read the database")
        return output.lineSequence().mapNotNull(::parseRow).toList()
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
    }
}
