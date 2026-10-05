package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.ScanBudget
import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Mistral Vibe sessions (`mistral-vibe` on PyPI, the `vibe.core.session` and `vibe.core.paths` modules of the package):
 * `<vibe home>/logs/session/session_<yyyymmdd>_<hhmmss>_<short id>/` holds `meta.json` (`session_id`,
 * `start_time`/`end_time`, `title`, `parent_session_id`, `archived_at`, `environment.working_directory`) and
 * `messages.jsonl`, one message per line; a typed prompt is `{"role": "user", "injected": false, ...}`, harness-injected
 * context carries `"injected": true`. Sub-agent sessions (with a `parent_session_id`) and archived sessions are not
 * listed. The vibe home is `~/.vibe`, or `VIBE_HOME`. `active/` (lock files) is not a session.
 */
class VibeProjectProvider(
    private val vibeHome: Path = defaultVibeHome(),
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val sessionsDirectory = vibeHome.resolve(LOGS_DIRECTORY).resolve(SESSION_DIRECTORY)

    override fun isAvailable(): Boolean = Files.isDirectory(sessionsDirectory, LinkOption.NOFOLLOW_LINKS)

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()
        val budget = ScanBudget(MAX_SCAN_ENTRIES)
        val sessions = mutableListOf<RawAgentProject>()
        var skipped = 0
        LocalSessionSupport.listDirectories(sessionsDirectory, budget)
            .filter { it.fileName.toString().startsWith(SESSION_PREFIX) }
            .forEach { sessionDirectory ->
                val meta = sessionDirectory.resolve(META_FILE)
                if (!Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS)) return@forEach
                val session = runCatching { parseSession(sessionDirectory, meta) }.getOrNull()
                if (session == null) skipped++ else sessions += session
            }
        if (skipped > 0) LOG.fine("[ProjectDiscovery] Mistral Vibe: skipped $skipped malformed, archived or sub-agent sessions")
        return LocalSessionSupport.deduplicate(sessions)
    }

    private fun parseSession(sessionDirectory: Path, meta: Path): RawAgentProject? {
        val json = LocalSessionSupport.readBoundedText(meta, MAX_META_CHARACTERS) ?: return null
        val fields = MetadataJsonParser.topLevelStringFields(json, META_STRING_FIELDS)
        if (!fields[PARENT_SESSION_FIELD].isNullOrBlank() || !fields[ARCHIVED_FIELD].isNullOrBlank()) return null
        val sessionId = fields[SESSION_ID_FIELD]?.takeIf { it.isNotBlank() } ?: return null
        val cwd = MetadataJsonParser.stringAtPath(json, ENVIRONMENT_FIELD, WORKING_DIRECTORY_FIELD)?.takeIf { it.isNotBlank() }
            ?: fields[ORIGIN_DIRECTORY_FIELD]?.takeIf { it.isNotBlank() }
            ?: return null
        val startedAt = LocalSessionSupport.parseTimestamp(fields[START_TIME_FIELD])
        val endedAt = LocalSessionSupport.parseTimestamp(fields[END_TIME_FIELD])
        val messages = sessionDirectory.resolve(MESSAGES_FILE)
        val tally = UserMessageTally.scanJsonl(messages, SCAN_MARKERS) { line ->
            val message = MetadataJsonParser.topLevelStringFields(line, MESSAGE_FIELDS)
            if (message[ROLE_FIELD] != USER_ROLE) return@scanJsonl
            if (MetadataJsonParser.topLevelBooleanFields(line, INJECTED_ONLY)[INJECTED_FIELD] == true) return@scanJsonl
            val content = message[CONTENT_FIELD]
            if (content.isNullOrBlank()) return@scanJsonl
            add(content)
        }
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = cwd,
            sessionId = sessionId,
            startedAt = startedAt,
            updatedAt = LocalSessionSupport.latest(startedAt, endedAt)
                ?: LocalSessionSupport.modifiedAt(messages) ?: LocalSessionSupport.modifiedAt(meta),
            sourcePath = sessionDirectory.toAbsolutePath().normalize().toString(),
            metadata = tally?.metadata().orEmpty() + listOfNotNull(fields[TITLE_FIELD]?.takeIf { it.isNotBlank() }?.let { TITLE_FIELD to it }),
        )
    }

    companion object {
        private const val AGENT_ID = "vibe"
        private const val LOGS_DIRECTORY = "logs"
        private const val SESSION_DIRECTORY = "session"
        private const val SESSION_PREFIX = "session_"
        private const val META_FILE = "meta.json"
        private const val MESSAGES_FILE = "messages.jsonl"
        private const val MAX_SCAN_ENTRIES = 50_000
        private const val MAX_META_CHARACTERS = 4 * 1024 * 1024
        private const val SESSION_ID_FIELD = "session_id"
        private const val PARENT_SESSION_FIELD = "parent_session_id"
        private const val ARCHIVED_FIELD = "archived_at"
        private const val START_TIME_FIELD = "start_time"
        private const val END_TIME_FIELD = "end_time"
        private const val TITLE_FIELD = "title"
        private const val ORIGIN_DIRECTORY_FIELD = "origin_directory"
        private const val ENVIRONMENT_FIELD = "environment"
        private const val WORKING_DIRECTORY_FIELD = "working_directory"
        private const val ROLE_FIELD = "role"
        private const val USER_ROLE = "user"
        private const val CONTENT_FIELD = "content"
        private const val INJECTED_FIELD = "injected"
        private val META_STRING_FIELDS = setOf(
            SESSION_ID_FIELD, PARENT_SESSION_FIELD, ARCHIVED_FIELD, START_TIME_FIELD, END_TIME_FIELD, TITLE_FIELD, ORIGIN_DIRECTORY_FIELD,
        )
        private val MESSAGE_FIELDS = setOf(ROLE_FIELD, CONTENT_FIELD)
        private val INJECTED_ONLY = setOf(INJECTED_FIELD)
        // Python's json.dumps writes `"role": "user"`; accept the compact form too.
        private val SCAN_MARKERS = listOf("\"role\": \"user\"", "\"role\":\"user\"")
        private val LOG: Logger = Logger.getLogger(VibeProjectProvider::class.java.name)

        private fun defaultVibeHome(): Path =
            EnvHomeDirectorySupport.resolveGuarded("VIBE_HOME", Path.of(System.getProperty("user.home")), ".vibe")
    }
}
