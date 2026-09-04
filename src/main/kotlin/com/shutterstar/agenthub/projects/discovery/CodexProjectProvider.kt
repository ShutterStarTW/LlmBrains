package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.latest
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.readBoundedLine
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.readTailLines
import com.shutterstar.agenthub.projects.model.ProjectComparators
import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.logging.Logger
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

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
        sessionDirectories
            .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            .forEach { sessionDirectory ->
                if (remainingScanEntries == 0) return@forEach
                var scannedEntries = 0
                Files.walk(sessionDirectory, MAX_SCAN_DEPTH).use { paths ->
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
                                sessions += session
                            }
                        }
                }
                remainingScanEntries -= scannedEntries
            }
        if (skippedSessions > 0) {
            LOG.fine("[ProjectDiscovery] Codex: skipped $skippedSessions malformed or unreadable sessions")
        }
        return deduplicate(sessions)
    }

    private fun parseSession(sessionFile: Path): RawAgentProject? {
        var metadata: SessionMetadata? = null
        var remainingCharacters = MAX_HEADER_CHARACTERS
        var linesRead = 0
        Files.newBufferedReader(sessionFile).use { reader ->
            while (metadata == null && linesRead < MAX_HEADER_LINES && remainingCharacters > 0) {
                val line = readBoundedLine(reader, remainingCharacters, MAX_LINE_CHARACTERS) ?: break
                remainingCharacters -= line.charactersConsumed
                linesRead++
                val text = line.text ?: continue
                val topLevel = MetadataJsonParser.topLevelStringFields(text, TOP_LEVEL_FIELDS)
                if (topLevel[TYPE_FIELD] != SESSION_META_TYPE) continue
                val payload = MetadataJsonParser.objectStringFields(text, PAYLOAD_FIELD, PAYLOAD_FIELDS)
                val projectPath = payload[WORKING_DIRECTORY_FIELD]?.takeIf { it.isNotBlank() } ?: continue
                val sessionId = payload[ID_FIELD]
                    ?.takeIf { it.isNotBlank() }
                    ?: payload[LEGACY_SESSION_ID_FIELD]?.takeIf { it.isNotBlank() }
                    ?: sessionIdFromFileName(sessionFile)
                val startedAt = parseTimestamp(payload[TIMESTAMP_FIELD] ?: topLevel[TIMESTAMP_FIELD])
                metadata = SessionMetadata(sessionId, projectPath, startedAt)
            }
        }
        val resolved = metadata ?: return null
        val lastEventAt = findLastEventTimestamp(sessionFile)
        val fileModifiedAt = runCatching { Files.getLastModifiedTime(sessionFile, LinkOption.NOFOLLOW_LINKS) }
            .getOrNull()
            ?.toInstant()
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = resolved.projectPath,
            sessionId = resolved.sessionId,
            startedAt = resolved.startedAt,
            updatedAt = latest(resolved.startedAt, lastEventAt, fileModifiedAt),
            sourcePath = sessionFile.toAbsolutePath().normalize().toString(),
        )
    }

    private fun findLastEventTimestamp(sessionFile: Path): Instant? =
        readTailLines(sessionFile, MAX_TAIL_BYTES, MAX_TAIL_LINES)
            .asSequence()
            .mapNotNull { line ->
                MetadataJsonParser.topLevelStringFields(line, setOf(TIMESTAMP_FIELD))[TIMESTAMP_FIELD]
            }
            .mapNotNull(::parseTimestamp)
            .firstOrNull()

    private fun deduplicate(sessions: List<RawAgentProject>): List<RawAgentProject> {
        val bySessionId = linkedMapOf<String, RawAgentProject>()
        sessions.forEach { candidate ->
            val existing = bySessionId[candidate.sessionId]
            if (existing == null || compareSessions(candidate, existing) > 0) {
                bySessionId[candidate.sessionId] = candidate
            }
        }
        return bySessionId.values.sortedWith(ProjectComparators.rawAgentProjectByRecency)
    }

    private fun compareSessions(first: RawAgentProject, second: RawAgentProject): Int {
        val activityComparison = (first.updatedAt ?: first.startedAt ?: Instant.MIN)
            .compareTo(second.updatedAt ?: second.startedAt ?: Instant.MIN)
        if (activityComparison != 0) return activityComparison
        return first.sourcePath.orEmpty().compareTo(second.sourcePath.orEmpty())
    }

    private fun sessionIdFromFileName(sessionFile: Path): String {
        val name = sessionFile.nameWithoutExtension
        return SESSION_ID_SUFFIX.find(name)?.groupValues?.get(1) ?: name
    }

    private fun parseTimestamp(value: String?): Instant? = try {
        value?.let(Instant::parse)
    } catch (_: DateTimeParseException) {
        null
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
        private const val JSONL_EXTENSION = "jsonl"
        private const val MAX_SCAN_DEPTH = 4
        private const val MAX_SCAN_ENTRIES = 50_000
        private const val MAX_HEADER_LINES = 32
        private const val MAX_HEADER_CHARACTERS = 512 * 1024
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

        private fun defaultCodexDirectory(): Path {
            val configured = System.getenv("CODEX_HOME")?.trim()?.takeIf { it.isNotEmpty() }
            return configured?.let { runCatching { Path.of(it) }.getOrNull() }
                ?: Path.of(System.getProperty("user.home"), ".codex")
        }
    }
}
