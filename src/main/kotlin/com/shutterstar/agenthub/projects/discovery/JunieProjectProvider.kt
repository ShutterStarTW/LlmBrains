package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.ScanBudget
import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Junie CLI sessions: `<junie home>/sessions/<sessionId>/summary.json` (`sessionId`, `createdAt`, `updatedAt` in epoch
 * milliseconds, `projectDir`, `lifecycle`) plus `events.jsonl`, where every typed prompt is a `UserPromptEvent` record
 * (`prompt`, `presentablePrompt`). `sessions/index.jsonl` repeats the summary of each session and is the fallback when a
 * session folder has no `summary.json`. `transcript.md` is not read. A folder with nothing but an empty transcript (a
 * session that never ran a task) has no summary and is not listed, and neither is a session whose event stream holds no
 * prompt. The junie home is `~/.junie`, or `JUNIE_HOME` (docs: junie.jetbrains.com/docs/environment-variables.html).
 */
class JunieProjectProvider(
    private val junieHome: Path = defaultJunieHome(),
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val sessionsDirectory = junieHome.resolve(SESSIONS_DIRECTORY)

    override fun isAvailable(): Boolean = Files.isDirectory(sessionsDirectory, LinkOption.NOFOLLOW_LINKS)

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()
        val budget = ScanBudget(MAX_SCAN_ENTRIES)
        val indexed = readIndex()
        val sessions = mutableListOf<RawAgentProject>()
        var skipped = 0
        LocalSessionSupport.listDirectories(sessionsDirectory, budget).forEach { sessionDirectory ->
            val summaryFile = sessionDirectory.resolve(SUMMARY_FILE)
            val hasSummary = Files.isRegularFile(summaryFile, LinkOption.NOFOLLOW_LINKS)
            val summary = if (hasSummary) {
                runCatching { LocalSessionSupport.readBoundedText(summaryFile, MAX_SUMMARY_CHARACTERS)?.let(::parseSummary) }.getOrNull()
            } else {
                indexed[sessionDirectory.fileName.toString()]
            }
            if (summary == null) {
                if (hasSummary) skipped++
                return@forEach
            }
            val session = runCatching { toRawProject(sessionDirectory, summary) }.getOrNull()
            if (session == null) skipped++ else sessions += session
        }
        if (skipped > 0) LOG.fine("[ProjectDiscovery] Junie: skipped $skipped malformed or empty sessions")
        return LocalSessionSupport.deduplicate(sessions)
    }

    private class Summary(val sessionId: String, val projectDir: String, val createdAt: Long?, val updatedAt: Long?)

    private fun parseSummary(json: String): Summary? {
        val strings = MetadataJsonParser.topLevelStringFields(json, SUMMARY_STRING_FIELDS)
        val times = MetadataJsonParser.topLevelLongFields(json, SUMMARY_TIME_FIELDS)
        val sessionId = strings[SESSION_ID_FIELD]?.takeIf { it.isNotBlank() } ?: return null
        val projectDir = strings[PROJECT_DIR_FIELD]?.takeIf { it.isNotBlank() } ?: return null
        return Summary(sessionId, projectDir, times[CREATED_AT_FIELD], times[UPDATED_AT_FIELD])
    }

    /** `index.jsonl`: one summary per line, keyed by session id. Unreadable or oversized: no fallback entries. */
    private fun readIndex(): Map<String, Summary> {
        val index = sessionsDirectory.resolve(INDEX_FILE)
        if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) return emptyMap()
        val result = linkedMapOf<String, Summary>()
        runCatching {
            LocalSessionSupport.scanHeaderLines(index, MAX_INDEX_LINES, MAX_INDEX_LINE_CHARACTERS) { line ->
                parseSummary(line)?.let { result[it.sessionId] = it }
                false
            }
        }
        return result
    }

    private fun toRawProject(sessionDirectory: Path, summary: Summary): RawAgentProject? {
        val events = sessionDirectory.resolve(EVENTS_FILE)
        val tally = UserMessageTally.scanJsonl(events, SCAN_MARKERS) { line ->
            val prompt = MetadataJsonParser.topLevelStringFields(line, PROMPT_FIELDS)
            if (prompt[KIND_FIELD] != USER_PROMPT_KIND) return@scanJsonl
            add(prompt[PRESENTABLE_PROMPT_FIELD] ?: prompt[PROMPT_FIELD])
        }
        // A readable event stream without a single prompt is a session that never ran a task.
        if (tally != null && tally.count == 0) return null
        val createdAt = LocalSessionSupport.epochTimestamp(summary.createdAt?.takeIf { it > 0 })
        val updatedAt = LocalSessionSupport.epochTimestamp(summary.updatedAt?.takeIf { it > 0 })
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = summary.projectDir,
            sessionId = summary.sessionId,
            startedAt = createdAt,
            updatedAt = LocalSessionSupport.latest(createdAt, updatedAt) ?: LocalSessionSupport.modifiedAt(events),
            sourcePath = sessionDirectory.toAbsolutePath().normalize().toString(),
            metadata = tally?.metadata().orEmpty(),
        )
    }

    companion object {
        private const val AGENT_ID = "junie"
        private const val SESSIONS_DIRECTORY = "sessions"
        private const val SUMMARY_FILE = "summary.json"
        private const val EVENTS_FILE = "events.jsonl"
        private const val INDEX_FILE = "index.jsonl"
        private const val MAX_SCAN_ENTRIES = 50_000
        private const val MAX_SUMMARY_CHARACTERS = 64 * 1024
        private const val MAX_INDEX_LINES = 50_000
        private const val MAX_INDEX_LINE_CHARACTERS = 16 * 1024
        private const val SESSION_ID_FIELD = "sessionId"
        private const val PROJECT_DIR_FIELD = "projectDir"
        private const val CREATED_AT_FIELD = "createdAt"
        private const val UPDATED_AT_FIELD = "updatedAt"
        private const val KIND_FIELD = "kind"
        private const val USER_PROMPT_KIND = "UserPromptEvent"
        private const val PROMPT_FIELD = "prompt"
        private const val PRESENTABLE_PROMPT_FIELD = "presentablePrompt"
        private val SUMMARY_STRING_FIELDS = setOf(SESSION_ID_FIELD, PROJECT_DIR_FIELD)
        private val SUMMARY_TIME_FIELDS = setOf(CREATED_AT_FIELD, UPDATED_AT_FIELD)
        private val PROMPT_FIELDS = setOf(KIND_FIELD, PROMPT_FIELD, PRESENTABLE_PROMPT_FIELD)
        private val SCAN_MARKERS = listOf(USER_PROMPT_KIND)
        private val LOG: Logger = Logger.getLogger(JunieProjectProvider::class.java.name)

        private fun defaultJunieHome(): Path =
            EnvHomeDirectorySupport.resolveGuarded("JUNIE_HOME", Path.of(System.getProperty("user.home")), ".junie")
    }
}
