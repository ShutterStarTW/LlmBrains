package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

class KiroProjectProvider(
    private val kiroDirectory: Path = defaultKiroDirectory(),
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val sessionsDirectory = kiroDirectory.resolve(SESSIONS_PATH)

    override fun isAvailable(): Boolean = Files.isDirectory(sessionsDirectory, LinkOption.NOFOLLOW_LINKS)

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()
        val sessions = runCatching {
            Files.list(sessionsDirectory).use { files ->
                files
                    .limit(MAX_SCAN_ENTRIES.toLong())
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .filter { it.extension.equals(JSON_EXTENSION, ignoreCase = true) }
                    .sorted()
                    .map { runCatching { parseSession(it) }.getOrNull() }
                    .filter { it != null }
                    .map { it!! }
                    .toList()
            }
        }.getOrDefault(emptyList())
        return LocalSessionSupport.deduplicate(sessions)
    }

    private fun parseSession(sessionFile: Path): RawAgentProject? {
        val json = LocalSessionSupport.readBoundedText(sessionFile, MAX_JSON_CHARACTERS) ?: return null
        val fields = MetadataJsonParser.topLevelStringFields(json, STRING_FIELDS)
        val longs = MetadataJsonParser.topLevelLongFields(json, TIME_FIELDS)
        val projectPath = fields[CWD_FIELD]?.takeIf { it.isNotBlank() } ?: return null
        val sessionId = fields[SESSION_ID_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[ID_FIELD]?.takeIf { it.isNotBlank() }
            ?: sessionFile.nameWithoutExtension
        val startedAt = LocalSessionSupport.parseTimestamp(fields[CREATED_AT_FIELD])
            ?: LocalSessionSupport.epochTimestamp(longs[CREATED_AT_FIELD])
        val recordedUpdate = LocalSessionSupport.parseTimestamp(fields[UPDATED_AT_FIELD])
            ?: LocalSessionSupport.epochTimestamp(longs[UPDATED_AT_FIELD])
        val title = fields[TITLE_FIELD]?.takeIf { it.isNotBlank() }
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = projectPath,
            sessionId = sessionId,
            startedAt = startedAt,
            updatedAt = LocalSessionSupport.latest(startedAt, recordedUpdate, LocalSessionSupport.modifiedAt(sessionFile)),
            sourcePath = sessionFile.toAbsolutePath().normalize().toString(),
            metadata = title?.let { mapOf(TITLE_FIELD to it) }.orEmpty(),
        )
    }

    companion object {
        private const val AGENT_ID = "kiro"
        private val SESSIONS_PATH = Path.of("sessions", "cli")
        private const val JSON_EXTENSION = "json"
        private const val MAX_SCAN_ENTRIES = 20_000
        private const val MAX_JSON_CHARACTERS = 256 * 1024
        private const val SESSION_ID_FIELD = "session_id"
        private const val ID_FIELD = "id"
        private const val CWD_FIELD = "cwd"
        private const val CREATED_AT_FIELD = "created_at"
        private const val UPDATED_AT_FIELD = "updated_at"
        private const val TITLE_FIELD = "title"
        private val STRING_FIELDS = setOf(
            SESSION_ID_FIELD,
            ID_FIELD,
            CWD_FIELD,
            CREATED_AT_FIELD,
            UPDATED_AT_FIELD,
            TITLE_FIELD,
        )
        private val TIME_FIELDS = setOf(CREATED_AT_FIELD, UPDATED_AT_FIELD)

        private fun defaultKiroDirectory(): Path {
            val configured = System.getenv("KIRO_HOME")?.trim()?.takeIf { it.isNotEmpty() }
            return configured?.let { runCatching { Path.of(it) }.getOrNull() }
                ?: Path.of(System.getProperty("user.home"), ".kiro")
        }
    }
}
