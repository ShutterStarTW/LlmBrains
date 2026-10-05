package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.ScanBudget
import com.shutterstar.agenthub.environment.discovery.OmpHomeSupport
import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.logging.Logger
import kotlin.io.path.extension

/**
 * Oh My Pi (`omp`) sessions: `<agent dir>/sessions/<encoded-cwd>/<timestamp>_<sessionId>.jsonl`. The directory name is a
 * lossy encoding of the cwd, so the working directory is read from the `type:"session"` header (which follows the
 * fixed-width `type:"title"` slot of current files; legacy files start with the header). Only the top-level `*.jsonl`
 * of each bucket is a session — the same-named sub-directories hold artifacts and sub-agent transcripts.
 * A user message is a `type:"message"` entry with `role:"user"` and `attribution:"user"` (or none, in older files);
 * agent-injected prompts carry `attribution:"agent"` or are `custom_message` entries.
 */
class OmpProjectProvider(
    private val agentDirectory: Path = OmpHomeSupport.agentDirectory(Path.of(System.getProperty("user.home"))),
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val sessionsDirectory = agentDirectory.resolve(SESSIONS_DIRECTORY)

    override fun isAvailable(): Boolean = Files.isDirectory(sessionsDirectory, LinkOption.NOFOLLOW_LINKS)

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()
        val budget = ScanBudget(MAX_SCAN_ENTRIES)
        val sessions = mutableListOf<RawAgentProject>()
        var skipped = 0
        LocalSessionSupport.listDirectories(sessionsDirectory, budget).forEach { bucket ->
            sessionFiles(bucket, budget).forEach { file ->
                val session = runCatching { parseSession(file) }.getOrNull()
                if (session == null) skipped++ else sessions += session
            }
        }
        if (skipped > 0) LOG.fine("[ProjectDiscovery] Oh My Pi: skipped $skipped malformed or unreadable sessions")
        return LocalSessionSupport.deduplicate(sessions)
    }

    private fun sessionFiles(bucket: Path, budget: ScanBudget): List<Path> {
        if (!budget.hasRemaining()) return emptyList()
        return runCatching {
            Files.list(bucket).use { paths ->
                paths.limit(budget.remaining().toLong()).toList()
                    .also { budget.consume(it.size) }
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.extension.equals(JSONL_EXTENSION, ignoreCase = true) }
                    .sorted()
            }
        }.getOrDefault(emptyList())
    }

    private fun parseSession(file: Path): RawAgentProject? {
        var sessionId: String? = null
        var cwd: String? = null
        var startedAt: Instant? = null
        var title: String? = null
        LocalSessionSupport.scanHeaderLines(file, MAX_HEADER_LINES, MAX_LINE_CHARACTERS) { text ->
            val fields = MetadataJsonParser.topLevelStringFields(text, HEADER_FIELDS)
            if (fields[TYPE_FIELD] != SESSION_TYPE) return@scanHeaderLines false
            sessionId = fields[ID_FIELD]?.takeIf { it.isNotBlank() }
            cwd = fields[CWD_FIELD]?.takeIf { it.isNotBlank() }
            startedAt = LocalSessionSupport.parseTimestamp(fields[TIMESTAMP_FIELD])
            title = fields[TITLE_FIELD]?.takeIf { it.isNotBlank() }
            true
        }
        val resolvedId = sessionId ?: return null
        val resolvedCwd = cwd ?: return null
        val statistics = SessionStatisticsAccumulator(agentId)
        val tally = UserMessageTally.scanJsonl(file, SCAN_MARKERS) { line ->
            if (MetadataJsonParser.topLevelStringFields(line, TYPE_ONLY)[TYPE_FIELD] != MESSAGE_TYPE) return@scanJsonl
            if (MetadataJsonParser.stringAtPath(line, MESSAGE_FIELD, ROLE_FIELD) != USER_ROLE) return@scanJsonl
            val attribution = MetadataJsonParser.stringAtPath(line, MESSAGE_FIELD, ATTRIBUTION_FIELD)
            if (attribution != null && attribution != USER_ATTRIBUTION) return@scanJsonl
            val text = MetadataJsonParser.rawPath(line, MESSAGE_FIELD, CONTENT_FIELD)?.let(MessageContentExtractor::text)
            add(text)
            statistics.userPrompt(text)
        }
        val modifiedAt = LocalSessionSupport.modifiedAt(file)
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = resolvedCwd,
            sessionId = resolvedId,
            startedAt = startedAt,
            updatedAt = LocalSessionSupport.latest(startedAt, modifiedAt),
            sourcePath = file.toAbsolutePath().normalize().toString(),
            metadata = tally?.metadata().orEmpty() + listOfNotNull(title?.let { TITLE_FIELD to it }),
            statistics = statistics.snapshot(),
        )
    }

    private companion object {
        const val AGENT_ID = "omp"
        const val SESSIONS_DIRECTORY = "sessions"
        const val JSONL_EXTENSION = "jsonl"
        const val MAX_SCAN_ENTRIES = 50_000
        const val MAX_HEADER_LINES = 8
        const val MAX_LINE_CHARACTERS = 256 * 1024
        const val TYPE_FIELD = "type"
        const val SESSION_TYPE = "session"
        const val MESSAGE_TYPE = "message"
        const val ID_FIELD = "id"
        const val CWD_FIELD = "cwd"
        const val TIMESTAMP_FIELD = "timestamp"
        const val TITLE_FIELD = "title"
        const val MESSAGE_FIELD = "message"
        const val ROLE_FIELD = "role"
        const val ATTRIBUTION_FIELD = "attribution"
        const val CONTENT_FIELD = "content"
        const val USER_ROLE = "user"
        const val USER_ATTRIBUTION = "user"
        val HEADER_FIELDS = setOf(TYPE_FIELD, ID_FIELD, CWD_FIELD, TIMESTAMP_FIELD, TITLE_FIELD)
        val TYPE_ONLY = setOf(TYPE_FIELD)
        val SCAN_MARKERS = listOf("\"role\":\"user\"")
        val LOG: Logger = Logger.getLogger(OmpProjectProvider::class.java.name)
    }
}
