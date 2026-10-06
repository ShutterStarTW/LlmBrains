package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.ScanBudget
import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.logging.Logger
import com.shutterstar.agenthub.AgentRuntime

/**
 * Kimi Code CLI sessions (the npm `@moonshot-ai/kimi-code`; the archived Python `kimi-cli` stored its data in `~/.kimi` and
 * is not read): `<data root>/sessions/<workDirKey>/<sessionId>/state.json` holds the session metadata (`cwd`, `title`,
 * `createdAt`, `updatedAt`, `archived`); `agents/main/wire.jsonl` is the main agent's event stream, where every user
 * turn is a `turn_begin` record with the typed prompt in `userInput`. Sub-agent wire files and archived sessions are not
 * counted. The data root is `~/.kimi-code`, or `KIMI_CODE_HOME` (docs: `configuration/data-locations.md`).
 */
class KimiProjectProvider(
    private val dataDirectory: Path = defaultDataDirectory(),
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val sessionsDirectory = dataDirectory.resolve(SESSIONS_DIRECTORY)

    override fun isAvailable(): Boolean = Files.isDirectory(sessionsDirectory, LinkOption.NOFOLLOW_LINKS)

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()
        val budget = ScanBudget(MAX_SCAN_ENTRIES)
        val sessions = mutableListOf<RawAgentProject>()
        var skipped = 0
        LocalSessionSupport.listDirectories(sessionsDirectory, budget)
            .filterNot { it.fileName.toString().startsWith(".") }
            .forEach { workDirBucket ->
                LocalSessionSupport.listDirectories(workDirBucket, budget).forEach { sessionDirectory ->
                    val state = sessionDirectory.resolve(STATE_FILE)
                    if (!Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS)) return@forEach
                    val session = runCatching { parseSession(sessionDirectory, state) }.getOrNull()
                    if (session == null) skipped++ else sessions += session
                }
            }
        if (skipped > 0) LOG.fine("[ProjectDiscovery] Kimi Code: skipped $skipped malformed or archived sessions")
        return LocalSessionSupport.deduplicate(sessions)
    }

    private fun parseSession(sessionDirectory: Path, state: Path): RawAgentProject? {
        val json = LocalSessionSupport.readBoundedText(state, MAX_STATE_CHARACTERS) ?: return null
        if (MetadataJsonParser.topLevelBooleanFields(json, ARCHIVED_ONLY)[ARCHIVED_FIELD] == true) return null
        val strings = MetadataJsonParser.topLevelStringFields(json, STATE_STRING_FIELDS)
        val cwd = strings[CWD_FIELD]?.takeIf { it.isNotBlank() } ?: return null
        val sessionId = strings[ID_FIELD]?.takeIf { it.isNotBlank() } ?: sessionDirectory.fileName.toString()
        val times = MetadataJsonParser.topLevelLongFields(json, TIME_FIELDS)
        val createdAt = timestamp(times[CREATED_AT_FIELD])
        val updatedAt = timestamp(times[UPDATED_AT_FIELD])
        val statistics = SessionStatisticsAccumulator(agentId)
        val wire = sessionDirectory.resolve(AGENTS_DIRECTORY).resolve(MAIN_AGENT_DIRECTORY).resolve(WIRE_FILE)
        val tally = UserMessageTally.scanJsonl(wire, SCAN_MARKERS) { line ->
            if (MetadataJsonParser.topLevelStringFields(line, TURN_FIELDS)[TYPE_FIELD] != TURN_BEGIN_TYPE) return@scanJsonl
            val input = MetadataJsonParser.topLevelStringFields(line, TURN_FIELDS)[USER_INPUT_FIELD]
            if (input.isNullOrBlank()) return@scanJsonl
            add(input)
            statistics.userPrompt(input)
        }
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = cwd,
            sessionId = sessionId,
            startedAt = createdAt,
            updatedAt = LocalSessionSupport.latest(createdAt, updatedAt) ?: LocalSessionSupport.modifiedAt(state),
            sourcePath = sessionDirectory.toAbsolutePath().normalize().toString(),
            metadata = tally?.metadata().orEmpty() + listOfNotNull(strings[TITLE_FIELD]?.takeIf { it.isNotBlank() }?.let { TITLE_FIELD to it }),
            statistics = statistics.snapshot(),
        )
    }

    /** Epoch milliseconds, or — for small values — seconds (the CLI itself normalizes both). */
    private fun timestamp(value: Long?): Instant? = value?.takeIf { it > 0 }?.let {
        runCatching { if (it > MILLIS_THRESHOLD) Instant.ofEpochMilli(it) else Instant.ofEpochSecond(it) }.getOrNull()
    }

    companion object {
        private const val AGENT_ID = "kimi"
        private const val SESSIONS_DIRECTORY = "sessions"
        private const val AGENTS_DIRECTORY = "agents"
        private const val MAIN_AGENT_DIRECTORY = "main"
        private const val WIRE_FILE = "wire.jsonl"
        private const val STATE_FILE = "state.json"
        private const val MAX_SCAN_ENTRIES = 50_000
        private const val MAX_STATE_CHARACTERS = 256 * 1024
        private const val MILLIS_THRESHOLD = 1_000_000_000_000L
        private const val ID_FIELD = "id"
        private const val CWD_FIELD = "cwd"
        private const val TITLE_FIELD = "title"
        private const val ARCHIVED_FIELD = "archived"
        private const val CREATED_AT_FIELD = "createdAt"
        private const val UPDATED_AT_FIELD = "updatedAt"
        private const val TYPE_FIELD = "type"
        private const val TURN_BEGIN_TYPE = "turn_begin"
        private const val USER_INPUT_FIELD = "userInput"
        private val STATE_STRING_FIELDS = setOf(ID_FIELD, CWD_FIELD, TITLE_FIELD)
        private val ARCHIVED_ONLY = setOf(ARCHIVED_FIELD)
        private val TIME_FIELDS = setOf(CREATED_AT_FIELD, UPDATED_AT_FIELD)
        private val TURN_FIELDS = setOf(TYPE_FIELD, USER_INPUT_FIELD)
        private val SCAN_MARKERS = listOf("\"turn_begin\"")
        private val LOG: Logger = Logger.getLogger(KimiProjectProvider::class.java.name)

        private fun defaultDataDirectory(): Path =
            EnvHomeDirectorySupport.resolveGuarded("KIMI_CODE_HOME", AgentRuntime.userHome(), ".kimi-code")
    }
}
