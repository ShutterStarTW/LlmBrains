package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.SafeFileTree

class QwenProjectProvider(
    private val qwenDirectory: Path = defaultQwenDirectory(),
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val projectsDirectory = qwenDirectory.resolve(PROJECTS_DIRECTORY)

    override fun isAvailable(): Boolean = Files.isDirectory(projectsDirectory, LinkOption.NOFOLLOW_LINKS)

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()
        val sessions = runCatching {
            SafeFileTree.walk(projectsDirectory, MAX_SCAN_DEPTH).use { paths ->
                paths
                    .limit(MAX_SCAN_ENTRIES.toLong())
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .filter(::isSessionFile)
                    .sorted()
                    .map { runCatching { parseSession(it) }.getOrNull() }
                    .filter { it != null }
                    .map { it!! }
                    .toList()
            }
        }.getOrDefault(emptyList())
        return LocalSessionSupport.deduplicate(sessions)
    }

    private fun isSessionFile(file: Path): Boolean {
        val name = file.fileName.toString()
        return name.endsWith(RUNTIME_SUFFIX, ignoreCase = true) ||
            file.extension.equals(JSONL_EXTENSION, ignoreCase = true)
    }

    private fun parseSession(sessionFile: Path): RawAgentProject? =
        if (sessionFile.fileName.toString().endsWith(RUNTIME_SUFFIX, ignoreCase = true)) {
            parseRuntimeSession(sessionFile)
        } else {
            parseSavedSession(sessionFile)
        }

    private fun parseRuntimeSession(sessionFile: Path): RawAgentProject? {
        val json = LocalSessionSupport.readBoundedText(sessionFile, MAX_JSON_CHARACTERS) ?: return null
        val fields = MetadataJsonParser.topLevelStringFields(json, METADATA_FIELDS)
        val longs = MetadataJsonParser.topLevelLongFields(json, TIME_FIELDS)
        val projectPath = fields[WORK_DIR_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[CWD_FIELD]?.takeIf { it.isNotBlank() }
            ?: return null
        val sessionId = fields[SESSION_ID_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[SESSION_ID_CAMEL_FIELD]?.takeIf { it.isNotBlank() }
            ?: sessionFile.fileName.toString().removeSuffix(RUNTIME_SUFFIX)
        val startedAt = timestamp(fields, longs, STARTED_AT_FIELD, CREATED_AT_FIELD, TIMESTAMP_FIELD)
        val updatedAt = timestamp(fields, longs, UPDATED_AT_FIELD, UPDATED_AT_CAMEL_FIELD)
        val raw = rawProject(sessionFile, sessionId, projectPath, startedAt, updatedAt, fields[TITLE_FIELD])
        // A runtime file with no recorded chat next to it: in the stores observed, these sessions
        // also had no entry in Qwen's own prompt log, i.e. they were closed without a prompt.
        // (When the chat exists, its own record carries the count and deduplication merges them.)
        val chat = sessionFile.resolveSibling("$sessionId.$JSONL_EXTENSION")
        if (Files.exists(chat, LinkOption.NOFOLLOW_LINKS)) return raw
        return raw.copy(metadata = raw.metadata + UserMessageTally().metadata())
    }

    private fun parseSavedSession(sessionFile: Path): RawAgentProject? {
        var sessionId: String? = null
        var projectPath: String? = null
        var startedAt: Instant? = null
        var title: String? = null
        LocalSessionSupport.scanHeaderLines(sessionFile, MAX_HEADER_LINES, MAX_LINE_CHARACTERS) { text ->
            val fields = MetadataJsonParser.topLevelStringFields(text, METADATA_FIELDS)
            val longs = MetadataJsonParser.topLevelLongFields(text, TIME_FIELDS)
            sessionId = sessionId
                ?: fields[SESSION_ID_CAMEL_FIELD]?.takeIf { it.isNotBlank() }
                ?: fields[SESSION_ID_FIELD]?.takeIf { it.isNotBlank() }
            projectPath = fields[CWD_FIELD]?.takeIf { it.isNotBlank() }
                ?: fields[WORK_DIR_FIELD]?.takeIf { it.isNotBlank() }
            startedAt = startedAt ?: timestamp(fields, longs, TIMESTAMP_FIELD, CREATED_AT_FIELD, STARTED_AT_FIELD)
            title = title ?: fields[TITLE_FIELD]?.takeIf { it.isNotBlank() }
            projectPath != null
        }
        val resolvedProject = projectPath ?: return null
        val resolvedId = sessionId ?: sessionFile.nameWithoutExtension
        return rawProject(
            sessionFile,
            resolvedId,
            resolvedProject,
            startedAt,
            findLastEventTimestamp(sessionFile),
            title,
        ).let { raw ->
            val statistics = SessionStatisticsAccumulator(agentId)
            val prompts = userMessages(sessionFile, statistics)
            raw.copy(metadata = raw.metadata + prompts?.metadata().orEmpty() + listOfNotNull(statistics.sessionTitle?.let { TITLE_FIELD to it }), statistics = statistics.snapshot())
        }
    }

    /** `{"type":"user","message":{"role":"user","parts":[{"text":"..."}]}}`; tool results are `type:"tool_result"`. */
    private fun userMessages(sessionFile: Path, statistics: SessionStatisticsAccumulator): UserMessageTally? =
        UserMessageTally.scanJsonl(sessionFile, USER_MARKERS) { line ->
            statistics.record(line)
            if (MetadataJsonParser.topLevelStringFields(line, setOf(TYPE_FIELD))[TYPE_FIELD] == USER_TYPE) {
                if (MetadataJsonParser.topLevelBooleanFields(line, setOf("isSidechain", "isMeta")).values.any { it }) return@scanJsonl
                val text = MessageContentExtractor.text(MetadataJsonParser.rawPath(line, MESSAGE_FIELD, PARTS_FIELD))
                add(text)
                statistics.userPrompt(text)
            }
        }

    private fun rawProject(
        sessionFile: Path,
        sessionId: String,
        projectPath: String,
        startedAt: Instant?,
        recordedUpdate: Instant?,
        title: String?,
    ) = RawAgentProject(
        agentId = agentId,
        rawProjectPath = projectPath,
        sessionId = sessionId,
        startedAt = startedAt,
        updatedAt = LocalSessionSupport.latest(startedAt, recordedUpdate, LocalSessionSupport.modifiedAt(sessionFile)),
        sourcePath = sessionFile.toAbsolutePath().normalize().toString(),
        metadata = title?.takeIf { it.isNotBlank() }?.let { mapOf(TITLE_FIELD to it) }.orEmpty(),
    )

    private fun findLastEventTimestamp(sessionFile: Path): Instant? =
        LocalSessionSupport.lastTimestamp(sessionFile, MAX_TAIL_BYTES, MAX_TAIL_LINES) { line ->
            val fields = MetadataJsonParser.topLevelStringFields(line, TIME_FIELDS)
            val longs = MetadataJsonParser.topLevelLongFields(line, TIME_FIELDS)
            timestamp(fields, longs, TIMESTAMP_FIELD, UPDATED_AT_FIELD, UPDATED_AT_CAMEL_FIELD)
        }

    private fun timestamp(
        fields: Map<String, String>,
        longs: Map<String, Long>,
        vararg names: String,
    ): Instant? = names.firstNotNullOfOrNull { name ->
        LocalSessionSupport.parseTimestamp(fields[name]) ?: LocalSessionSupport.epochTimestamp(longs[name])
    }

    companion object {
        private const val AGENT_ID = "qwen"
        private const val PROJECTS_DIRECTORY = "projects"
        private const val JSONL_EXTENSION = "jsonl"
        private const val RUNTIME_SUFFIX = ".runtime.json"
        private const val MAX_SCAN_DEPTH = 4
        private const val MAX_SCAN_ENTRIES = 20_000
        private const val MAX_JSON_CHARACTERS = 256 * 1024
        private const val MAX_HEADER_LINES = 32
        private const val MAX_LINE_CHARACTERS = 256 * 1024
        private const val MAX_TAIL_BYTES = 256 * 1024
        private const val MAX_TAIL_LINES = 200
        private const val SESSION_ID_FIELD = "session_id"
        private const val SESSION_ID_CAMEL_FIELD = "sessionId"
        private const val CWD_FIELD = "cwd"
        private const val WORK_DIR_FIELD = "work_dir"
        private const val STARTED_AT_FIELD = "started_at"
        private const val CREATED_AT_FIELD = "created_at"
        private const val UPDATED_AT_FIELD = "updated_at"
        private const val UPDATED_AT_CAMEL_FIELD = "updatedAt"
        private const val TIMESTAMP_FIELD = "timestamp"
        private const val TITLE_FIELD = "title"
        private const val TYPE_FIELD = "type"
        private const val USER_TYPE = "user"
        private const val MESSAGE_FIELD = "message"
        private const val PARTS_FIELD = "parts"
        private val USER_MARKERS = listOf("\"type\"")
        private val TIME_FIELDS = setOf(
            STARTED_AT_FIELD,
            CREATED_AT_FIELD,
            UPDATED_AT_FIELD,
            UPDATED_AT_CAMEL_FIELD,
            TIMESTAMP_FIELD,
        )
        private val METADATA_FIELDS = TIME_FIELDS + setOf(
            SESSION_ID_FIELD,
            SESSION_ID_CAMEL_FIELD,
            CWD_FIELD,
            WORK_DIR_FIELD,
            TITLE_FIELD,
        )

        // Sessions live under the runtime base directory: QWEN_RUNTIME_DIR, else QWEN_HOME, else ~/.qwen.
        private fun defaultQwenDirectory(): Path =
            EnvHomeDirectorySupport.resolveFirst("QWEN_RUNTIME_DIR", "QWEN_HOME") {
                AgentRuntime.userHome().resolve(".qwen")
            }
    }
}
