package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.epochTimestamp
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.latest
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.parseTimestamp
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.readBoundedText
import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.logging.Logger
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.SafeFileTree

class AntigravityProjectProvider(
    private val dataDirectory: Path = defaultDataDirectory(),
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val sessionDirectories: List<Path>
        get() {
            val candidates = mutableListOf(dataDirectory)

            val parent = dataDirectory.parent
            if (parent != null && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                val altAntigravity = parent.resolve("antigravity")
                if (altAntigravity != dataDirectory && Files.isDirectory(altAntigravity, LinkOption.NOFOLLOW_LINKS)) {
                    candidates.add(altAntigravity)
                }
            }
            return candidates.distinct()
        }

    override fun isAvailable(): Boolean =
        sessionDirectories.any { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()

        var skippedSessions = 0
        val sessions = mutableListOf<RawAgentProject>()
        val existingDirs = sessionDirectories.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }

        existingDirs.forEach { directory ->
            val result = discoverDirectory(directory)
            sessions += result.sessions
            skippedSessions += result.skippedSessions
        }

        if (skippedSessions > 0) {
            LOG.fine("[ProjectDiscovery] Antigravity: skipped $skippedSessions malformed or unreadable sessions")
        }
        return LocalSessionSupport.deduplicate(sessions)
    }

    private fun discoverDirectory(directory: Path): DirectoryDiscoveryResult = try {
        val sessions = mutableListOf<RawAgentProject>()
        var skippedSessions = 0

        SafeFileTree.walk(directory, MAX_SCAN_DEPTH).use { paths ->
            // The limit is a traversal budget, applied before sorting on purpose: sorting first would mean
            // walking (and holding) the whole tree, which is exactly what the budget prevents. Below the budget
            // the result is deterministic (sorted); above it, which files are reached follows the walk order.
            paths
                .limit(MAX_SCAN_ENTRIES.toLong())
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { isCandidateSessionFile(it) }
                .sorted()
                .forEach { sessionFile ->
                    val fileName = sessionFile.fileName.toString()
                    if (fileName.equals(CONVERSATION_METADATA_FILE, ignoreCase = true)) {
                        val metadataSessions = runCatching { parseConversationMetadata(sessionFile) }.getOrDefault(emptyList())
                        if (metadataSessions.isEmpty()) {
                            skippedSessions++
                        } else {
                            sessions.addAll(metadataSessions)
                        }
                    } else {
                        val session = runCatching { parseSession(sessionFile) }.getOrNull()
                        if (session == null) {
                            skippedSessions++
                        } else {
                            sessions.add(session)
                        }
                    }
                }
        }
        DirectoryDiscoveryResult(sessions, skippedSessions)
    } catch (_: Exception) {
        DirectoryDiscoveryResult(emptyList(), skippedSessions = 1)
    }

    private fun isCandidateSessionFile(file: Path): Boolean {
        if (isInScratchDirectory(file)) return false
        val ext = file.extension
        val fileName = file.fileName.toString()

        if (ext.equals(JSONL_EXTENSION, ignoreCase = true)) {
            return true
        }

        if (ext.equals(JSON_EXTENSION, ignoreCase = true)) {
            if (IGNORED_CONFIG_FILES.contains(fileName.lowercase())) {
                return false
            }
            return true
        }

        return false
    }

    private fun isInScratchDirectory(file: Path): Boolean {
        var current: Path? = file.parent
        while (current != null) {
            val dirName = current.fileName?.toString()
            if (dirName.equals(SCRATCH_DIRECTORY, ignoreCase = true)) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun parseSession(sessionFile: Path): RawAgentProject? {
        val ext = sessionFile.extension
        return if (ext.equals(JSON_EXTENSION, ignoreCase = true)) {
            parseJsonSession(sessionFile)
        } else {
            parseJsonlSession(sessionFile)
        }
    }

    private fun parseJsonSession(sessionFile: Path): RawAgentProject? {
        val json = readBoundedText(sessionFile, MAX_JSON_FILE_CHARACTERS) ?: return null
        val fields = MetadataJsonParser.topLevelStringFields(json, JSON_STRING_FIELDS)
        val projectPath = fields[WORKSPACE_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[CWD_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[PROJECT_PATH_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[PROJECT_PATH_SNAKE_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[WORKING_DIRECTORY_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[WORKING_DIRECTORY_CAMEL_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[DIRECTORY_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[WORKSPACE_ROOT_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[WORKSPACE_ROOT_CAMEL_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[RAW_PROJECT_PATH_FIELD]?.takeIf { it.isNotBlank() }
            ?: MetadataJsonParser.objectStringFields(json, WORKSPACE_FIELD, NESTED_WORKSPACE_FIELDS)["root"]
            ?: MetadataJsonParser.objectStringFields(json, WORKSPACE_FIELD, NESTED_WORKSPACE_FIELDS)["path"]
            ?: MetadataJsonParser.objectStringFields(json, PAYLOAD_FIELD, PAYLOAD_FIELDS)[CWD_FIELD]
            ?: MetadataJsonParser.objectStringFields(json, PAYLOAD_FIELD, PAYLOAD_FIELDS)[WORKSPACE_FIELD]
            ?: extractPathRegex(json)
            ?: return null

        val sessionId = fields[SESSION_ID_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[SESSION_ID_SNAKE_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[CONVERSATION_ID_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[CONVERSATION_ID_SNAKE_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[ID_FIELD]?.takeIf { it.isNotBlank() }
            ?: MetadataJsonParser.objectStringFields(json, PAYLOAD_FIELD, PAYLOAD_FIELDS)[ID_FIELD]
            ?: sessionIdFromFileOrDirectory(sessionFile)

        val timeFields = MetadataJsonParser.topLevelLongFields(json, TIME_FIELDS)
        val startedAt = parseTimestamp(fields[CREATED_AT_FIELD])
            ?: parseTimestamp(fields[CREATED_AT_CAMEL_FIELD])
            ?: parseTimestamp(fields[STARTED_AT_FIELD])
            ?: parseTimestamp(fields[STARTED_AT_CAMEL_FIELD])
            ?: parseTimestamp(fields[TIMESTAMP_FIELD])
            ?: parseTimestamp(fields[TIME_FIELD])
            ?: epochTimestamp(timeFields[CREATED_AT_FIELD] ?: timeFields[CREATED_FIELD] ?: timeFields[TIME_FIELD] ?: timeFields[STARTED_AT_FIELD])

        val updatedAt = parseTimestamp(fields[UPDATED_AT_FIELD])
            ?: parseTimestamp(fields[UPDATED_AT_CAMEL_FIELD])
            ?: parseTimestamp(fields[MODIFIED_AT_FIELD])
            ?: parseTimestamp(fields[MODIFIED_AT_CAMEL_FIELD])
            ?: parseTimestamp(fields[LAST_ACTIVITY_FIELD])
            ?: epochTimestamp(timeFields[UPDATED_AT_FIELD] ?: timeFields[UPDATED_FIELD] ?: timeFields[MODIFIED_AT_FIELD])

        val fileModifiedAt = runCatching { Files.getLastModifiedTime(sessionFile, LinkOption.NOFOLLOW_LINKS) }
            .getOrNull()
            ?.toInstant()

        val title = fields[TITLE_FIELD]
            ?: fields[NAME_FIELD]
            ?: fields[SUMMARY_FIELD]
            ?: fields[DESCRIPTION_FIELD]

        val metadata = mutableMapOf<String, String>()
        if (!title.isNullOrBlank()) {
            metadata[TITLE_FIELD] = title
        }

        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = normalizeProjectPath(projectPath),
            sessionId = sessionId,
            startedAt = startedAt,
            updatedAt = latest(startedAt, updatedAt, fileModifiedAt),
            sourcePath = sessionFile.toAbsolutePath().normalize().toString(),
            metadata = metadata,
        )
    }

    private fun parseJsonlSession(sessionFile: Path): RawAgentProject? {
        var sessionId: String? = null
        var projectPath: String? = null
        var startedAt: Instant? = null
        var title: String? = null

        LocalSessionSupport.scanHeaderLines(sessionFile, MAX_HEADER_LINES, MAX_LINE_CHARACTERS) { text ->

            val topLevel = MetadataJsonParser.topLevelStringFields(text, TOP_LEVEL_JSONL_FIELDS)

            if (sessionId == null) {
                sessionId = topLevel[SESSION_ID_FIELD]?.takeIf { it.isNotBlank() }
                    ?: topLevel[SESSION_ID_SNAKE_FIELD]?.takeIf { it.isNotBlank() }
                    ?: topLevel[CONVERSATION_ID_FIELD]?.takeIf { it.isNotBlank() }
                    ?: topLevel[CONVERSATION_ID_SNAKE_FIELD]?.takeIf { it.isNotBlank() }
                    ?: topLevel[ID_FIELD]?.takeIf { it.isNotBlank() }
            }

            if (projectPath == null) {
                projectPath = topLevel[WORKSPACE_FIELD]?.takeIf { it.isNotBlank() }
                    ?: topLevel[CWD_FIELD]?.takeIf { it.isNotBlank() }
                    ?: topLevel[PROJECT_PATH_FIELD]?.takeIf { it.isNotBlank() }
                    ?: topLevel[PROJECT_PATH_SNAKE_FIELD]?.takeIf { it.isNotBlank() }
                    ?: topLevel[WORKING_DIRECTORY_FIELD]?.takeIf { it.isNotBlank() }
                    ?: topLevel[WORKING_DIRECTORY_CAMEL_FIELD]?.takeIf { it.isNotBlank() }
                    ?: topLevel[DIRECTORY_FIELD]?.takeIf { it.isNotBlank() }
                    ?: topLevel[WORKSPACE_ROOT_FIELD]?.takeIf { it.isNotBlank() }
                    ?: topLevel[WORKSPACE_ROOT_CAMEL_FIELD]?.takeIf { it.isNotBlank() }
                    ?: topLevel[RAW_PROJECT_PATH_FIELD]?.takeIf { it.isNotBlank() }
                    ?: MetadataJsonParser.objectStringFields(text, WORKSPACE_FIELD, NESTED_WORKSPACE_FIELDS)["root"]
                    ?: MetadataJsonParser.objectStringFields(text, WORKSPACE_FIELD, NESTED_WORKSPACE_FIELDS)["path"]
                    ?: MetadataJsonParser.objectStringFields(text, ARGS_FIELD, PARAMETER_FIELDS)[CWD_PASCAL_FIELD]
                    ?: MetadataJsonParser.objectStringFields(text, ARGS_FIELD, PARAMETER_FIELDS)[CWD_FIELD]
                    ?: MetadataJsonParser.objectStringFields(text, ARGS_FIELD, PARAMETER_FIELDS)[DIRECTORY_PATH_FIELD]
                    ?: MetadataJsonParser.objectStringFields(text, ARGS_FIELD, PARAMETER_FIELDS)[SEARCH_DIRECTORY_FIELD]
                    ?: MetadataJsonParser.objectStringFields(text, ARGS_FIELD, PARAMETER_FIELDS)[SEARCH_PATH_FIELD]
                    ?: MetadataJsonParser.objectStringFields(text, PARAMETERS_FIELD, PARAMETER_FIELDS)[CWD_PASCAL_FIELD]
                    ?: MetadataJsonParser.objectStringFields(text, PARAMETERS_FIELD, PARAMETER_FIELDS)[CWD_FIELD]
                    ?: MetadataJsonParser.objectStringFields(text, PARAMETERS_FIELD, PARAMETER_FIELDS)[DIRECTORY_PATH_FIELD]
                    ?: MetadataJsonParser.objectStringFields(text, PARAMETERS_FIELD, PARAMETER_FIELDS)[SEARCH_DIRECTORY_FIELD]
                    ?: MetadataJsonParser.objectStringFields(text, PARAMETERS_FIELD, PARAMETER_FIELDS)[SEARCH_PATH_FIELD]
                    ?: MetadataJsonParser.objectStringFields(text, PAYLOAD_FIELD, PAYLOAD_FIELDS)[CWD_FIELD]
                    ?: MetadataJsonParser.objectStringFields(text, PAYLOAD_FIELD, PAYLOAD_FIELDS)[WORKSPACE_FIELD]
                    ?: extractPathRegex(text)
            }

            if (startedAt == null) {
                startedAt = parseTimestamp(topLevel[CREATED_AT_FIELD])
                    ?: parseTimestamp(topLevel[CREATED_AT_CAMEL_FIELD])
                    ?: parseTimestamp(topLevel[TIMESTAMP_FIELD])
                    ?: parseTimestamp(topLevel[STARTED_AT_FIELD])
                    ?: parseTimestamp(topLevel[STARTED_AT_CAMEL_FIELD])
                    ?: parseTimestamp(topLevel[TIME_FIELD])
            }

            if (title == null) {
                title = topLevel[TITLE_FIELD]?.takeIf { it.isNotBlank() }
                    ?: topLevel[SUMMARY_FIELD]?.takeIf { it.isNotBlank() }
            }

            sessionId != null && projectPath != null && startedAt != null
        }

        val resolvedProjectPath = projectPath ?: return null
        val resolvedSessionId = sessionId ?: sessionIdFromFileOrDirectory(sessionFile)
        val lastEventAt = findLastEventTimestamp(sessionFile)
        val fileModifiedAt = runCatching { Files.getLastModifiedTime(sessionFile, LinkOption.NOFOLLOW_LINKS) }
            .getOrNull()
            ?.toInstant()

        val metadata = mutableMapOf<String, String>()
        if (!title.isNullOrBlank()) {
            metadata[TITLE_FIELD] = title
        }
        val statistics = SessionStatisticsAccumulator(agentId)
        userMessages(sessionFile, statistics)?.takeIf { it.count > 0 }?.let { metadata += it.metadata() }

        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = normalizeProjectPath(resolvedProjectPath),
            sessionId = resolvedSessionId,
            startedAt = startedAt,
            updatedAt = latest(startedAt, lastEventAt, fileModifiedAt),
            sourcePath = sessionFile.toAbsolutePath().normalize().toString(),
            metadata = metadata,
            statistics = statistics.snapshot(),
        )
    }

    /**
     * Transcript steps the user typed: `{"source":"USER_EXPLICIT","type":"USER_INPUT","content":"..."}`.
     * Other JSONL files this provider accepts have none, so their tally stays empty.
     */
    private fun userMessages(sessionFile: Path, statistics: SessionStatisticsAccumulator): UserMessageTally? =
        UserMessageTally.scanJsonl(sessionFile, USER_INPUT_MARKERS) { line ->
            statistics.record(line)
            val fields = MetadataJsonParser.topLevelStringFields(line, STEP_FIELDS)
            if (fields[STEP_TYPE_FIELD] == USER_INPUT_TYPE) {
                add(fields[STEP_CONTENT_FIELD])
                statistics.userPrompt(fields[STEP_CONTENT_FIELD])
            }
        }

    private fun findLastEventTimestamp(sessionFile: Path): Instant? =
        LocalSessionSupport.lastTimestamp(sessionFile, MAX_TAIL_BYTES, MAX_TAIL_LINES) { line ->
            val fields = MetadataJsonParser.topLevelStringFields(line, TIMESTAMP_FIELDS)
            parseTimestamp(
                fields[CREATED_AT_FIELD]
                    ?: fields[CREATED_AT_CAMEL_FIELD]
                    ?: fields[TIMESTAMP_FIELD]
                    ?: fields[TIME_FIELD]
                    ?: fields[UPDATED_AT_FIELD]
                    ?: fields[UPDATED_AT_CAMEL_FIELD],
            )
        }

    private fun sessionIdFromFileOrDirectory(sessionFile: Path): String {
        val fileName = sessionFile.fileName.toString()
        val nameWithoutExt = sessionFile.nameWithoutExtension
        if (COMMON_GENERIC_FILENAMES.contains(fileName.lowercase())) {
            val parent = sessionFile.parent ?: return nameWithoutExt
            if (parent.fileName.toString().equals("logs", ignoreCase = true)) {
                val grandParent = parent.parent
                if (grandParent != null && grandParent.fileName.toString().equals(".system_generated", ignoreCase = true)) {
                    return grandParent.parent?.fileName?.toString() ?: parent.fileName.toString()
                }
                return grandParent?.fileName?.toString() ?: parent.fileName.toString()
            }
            if (parent.fileName.toString().equals(".system_generated", ignoreCase = true)) {
                return parent.parent?.fileName?.toString() ?: parent.fileName.toString()
            }
            return parent.fileName.toString()
        }
        return nameWithoutExt
    }

    private fun extractPathRegex(text: String): String? {
        val mappingMatch = WORKSPACE_MAPPING_REGEX.find(text)
        if (mappingMatch != null) {
            val matched = mappingMatch.groupValues[1].trim()
            if (matched.isNotBlank()) return matched
        }
        val cwdMatch = CWD_JSON_REGEX.find(text)
        if (cwdMatch != null) {
            val matched = cleanMatchedPath(cwdMatch.groupValues[1])
            if (matched.isNotBlank()) return matched
        }
        val workspaceMatch = WORKSPACE_JSON_REGEX.find(text)
        if (workspaceMatch != null) {
            val matched = cleanMatchedPath(workspaceMatch.groupValues[1])
            if (matched.isNotBlank()) return matched
        }
        val searchDirMatch = SEARCH_DIR_REGEX.find(text)
        if (searchDirMatch != null) {
            val matched = cleanMatchedPath(searchDirMatch.groupValues[1])
            if (matched.isNotBlank()) return matched
        }
        val dirPathMatch = DIR_PATH_REGEX.find(text)
        if (dirPathMatch != null) {
            val matched = cleanMatchedPath(dirPathMatch.groupValues[1])
            if (matched.isNotBlank()) return matched
        }
        val searchPathMatch = SEARCH_PATH_REGEX.find(text)
        if (searchPathMatch != null) {
            val matched = cleanMatchedPath(searchPathMatch.groupValues[1])
            if (matched.isNotBlank()) return matched
        }
        return null
    }

    private fun parseConversationMetadata(sessionFile: Path): List<RawAgentProject> {
        val text = readBoundedText(sessionFile, MAX_JSON_FILE_CHARACTERS) ?: return emptyList()
        val conversations = MetadataJsonParser.rawTopLevelField(text, CONVERSATIONS_FIELD)
            ?.let(MetadataJsonParser::objectEntries)
            ?: return emptyList()

        val fileModifiedAt = runCatching { Files.getLastModifiedTime(sessionFile, LinkOption.NOFOLLOW_LINKS) }
            .getOrNull()
            ?.toInstant()
        val sourcePath = sessionFile.toAbsolutePath().normalize().toString()

        return conversations.mapNotNull { (conversationId, rawConversation) ->
            val summary = MetadataJsonParser.rawTopLevelField(rawConversation, SUMMARY_FIELD) ?: return@mapNotNull null
            val rawPath = MetadataJsonParser.rawTopLevelField(summary, WORKSPACE_URIS_FIELD)
                ?.let(MetadataJsonParser::arrayStringElements)
                ?.firstOrNull { it.isNotBlank() }
                ?: return@mapNotNull null
            val fields = MetadataJsonParser.topLevelStringFields(
                summary,
                setOf(SUMMARY_ID_FIELD, SUMMARY_TITLE_FIELD, SUMMARY_PREVIEW_FIELD, SUMMARY_UPDATED_AT_FIELD),
            )
            val lastModified = MetadataJsonParser.topLevelStringFields(rawConversation, setOf(LAST_MODIFIED_FIELD))[LAST_MODIFIED_FIELD]
            val title = fields[SUMMARY_TITLE_FIELD]?.takeIf { it.isNotBlank() }
                ?: fields[SUMMARY_PREVIEW_FIELD]?.takeIf { it.isNotBlank() }

            RawAgentProject(
                agentId = agentId,
                rawProjectPath = normalizeProjectPath(rawPath),
                sessionId = fields[SUMMARY_ID_FIELD]?.takeIf { it.isNotBlank() } ?: conversationId,
                startedAt = null,
                updatedAt = parseTimestamp(fields[SUMMARY_UPDATED_AT_FIELD]) ?: parseTimestamp(lastModified) ?: fileModifiedAt,
                sourcePath = sourcePath,
                metadata = if (title != null) mapOf(TITLE_FIELD to title) else emptyMap(),
            )
        }
    }

    companion object {
        private const val AGENT_ID = "antigravity"
        private const val SCRATCH_DIRECTORY = "scratch"
        private const val JSONL_EXTENSION = "jsonl"
        private const val JSON_EXTENSION = "json"

        private const val MAX_SCAN_DEPTH = 5
        private const val MAX_SCAN_ENTRIES = 50_000
        private const val MAX_HEADER_LINES = 100
        private const val MAX_LINE_CHARACTERS = 64 * 1024
        private const val MAX_JSON_FILE_CHARACTERS = 256 * 1024
        private const val MAX_TAIL_BYTES = 256 * 1024
        private const val MAX_TAIL_LINES = 200

        private const val SESSION_ID_FIELD = "sessionId"
        private const val SESSION_ID_SNAKE_FIELD = "session_id"
        private const val CONVERSATION_ID_FIELD = "conversationId"
        private const val CONVERSATION_ID_SNAKE_FIELD = "conversation_id"
        private const val ID_FIELD = "id"

        private const val CWD_FIELD = "cwd"
        private const val CWD_PASCAL_FIELD = "Cwd"
        private const val WORKSPACE_FIELD = "workspace"
        private const val WORKSPACE_ROOT_FIELD = "workspace_root"
        private const val WORKSPACE_ROOT_CAMEL_FIELD = "workspaceRoot"
        private const val PROJECT_PATH_FIELD = "projectPath"
        private const val PROJECT_PATH_SNAKE_FIELD = "project_path"
        private const val WORKING_DIRECTORY_FIELD = "working_directory"
        private const val WORKING_DIRECTORY_CAMEL_FIELD = "workingDirectory"
        private const val DIRECTORY_FIELD = "directory"
        private const val DIRECTORY_PATH_FIELD = "DirectoryPath"
        private const val SEARCH_DIRECTORY_FIELD = "SearchDirectory"
        private const val SEARCH_PATH_FIELD = "SearchPath"
        private const val RAW_PROJECT_PATH_FIELD = "rawProjectPath"

        private const val CREATED_AT_FIELD = "created_at"
        private const val CREATED_AT_CAMEL_FIELD = "createdAt"
        private const val STARTED_AT_FIELD = "started_at"
        private const val STARTED_AT_CAMEL_FIELD = "startedAt"
        private const val TIMESTAMP_FIELD = "timestamp"
        private const val TIME_FIELD = "time"
        private const val CREATED_FIELD = "created"

        private const val UPDATED_AT_FIELD = "updated_at"
        private const val UPDATED_AT_CAMEL_FIELD = "updatedAt"
        private const val MODIFIED_AT_FIELD = "modified_at"
        private const val MODIFIED_AT_CAMEL_FIELD = "modifiedAt"
        private const val LAST_ACTIVITY_FIELD = "last_activity"
        private const val UPDATED_FIELD = "updated"

        private const val TITLE_FIELD = "title"
        private const val NAME_FIELD = "name"
        private const val SUMMARY_FIELD = "summary"
        private const val DESCRIPTION_FIELD = "description"
        private const val PAYLOAD_FIELD = "payload"
        private const val PARAMETERS_FIELD = "parameters"

        private val JSON_STRING_FIELDS = setOf(
            SESSION_ID_FIELD,
            SESSION_ID_SNAKE_FIELD,
            CONVERSATION_ID_FIELD,
            CONVERSATION_ID_SNAKE_FIELD,
            ID_FIELD,
            WORKSPACE_FIELD,
            CWD_FIELD,
            PROJECT_PATH_FIELD,
            PROJECT_PATH_SNAKE_FIELD,
            WORKING_DIRECTORY_FIELD,
            WORKING_DIRECTORY_CAMEL_FIELD,
            DIRECTORY_FIELD,
            WORKSPACE_ROOT_FIELD,
            WORKSPACE_ROOT_CAMEL_FIELD,
            RAW_PROJECT_PATH_FIELD,
            CREATED_AT_FIELD,
            CREATED_AT_CAMEL_FIELD,
            STARTED_AT_FIELD,
            STARTED_AT_CAMEL_FIELD,
            TIMESTAMP_FIELD,
            TIME_FIELD,
            UPDATED_AT_FIELD,
            UPDATED_AT_CAMEL_FIELD,
            MODIFIED_AT_FIELD,
            MODIFIED_AT_CAMEL_FIELD,
            LAST_ACTIVITY_FIELD,
            TITLE_FIELD,
            NAME_FIELD,
            SUMMARY_FIELD,
            DESCRIPTION_FIELD,
        )

        private val TOP_LEVEL_JSONL_FIELDS = setOf(
            SESSION_ID_FIELD,
            SESSION_ID_SNAKE_FIELD,
            CONVERSATION_ID_FIELD,
            CONVERSATION_ID_SNAKE_FIELD,
            ID_FIELD,
            WORKSPACE_FIELD,
            CWD_FIELD,
            PROJECT_PATH_FIELD,
            PROJECT_PATH_SNAKE_FIELD,
            WORKING_DIRECTORY_FIELD,
            WORKING_DIRECTORY_CAMEL_FIELD,
            DIRECTORY_FIELD,
            WORKSPACE_ROOT_FIELD,
            WORKSPACE_ROOT_CAMEL_FIELD,
            RAW_PROJECT_PATH_FIELD,
            CREATED_AT_FIELD,
            CREATED_AT_CAMEL_FIELD,
            STARTED_AT_FIELD,
            STARTED_AT_CAMEL_FIELD,
            TIMESTAMP_FIELD,
            TIME_FIELD,
            TITLE_FIELD,
            SUMMARY_FIELD,
        )

        private val TIMESTAMP_FIELDS = setOf(
            CREATED_AT_FIELD,
            CREATED_AT_CAMEL_FIELD,
            TIMESTAMP_FIELD,
            TIME_FIELD,
            UPDATED_AT_FIELD,
            UPDATED_AT_CAMEL_FIELD,
        )

        private val TIME_FIELDS = setOf(
            CREATED_AT_FIELD,
            CREATED_AT_CAMEL_FIELD,
            STARTED_AT_FIELD,
            STARTED_AT_CAMEL_FIELD,
            TIMESTAMP_FIELD,
            TIME_FIELD,
            CREATED_FIELD,
            UPDATED_AT_FIELD,
            UPDATED_AT_CAMEL_FIELD,
            MODIFIED_AT_FIELD,
            MODIFIED_AT_CAMEL_FIELD,
            LAST_ACTIVITY_FIELD,
            UPDATED_FIELD,
        )

        private val NESTED_WORKSPACE_FIELDS = setOf("root", "path", "directory")

        private val PARAMETER_FIELDS = setOf(
            CWD_PASCAL_FIELD,
            CWD_FIELD,
            DIRECTORY_PATH_FIELD,
            SEARCH_DIRECTORY_FIELD,
            SEARCH_PATH_FIELD,
        )

        private val PAYLOAD_FIELDS = setOf(
            ID_FIELD,
            SESSION_ID_FIELD,
            SESSION_ID_SNAKE_FIELD,
            CWD_FIELD,
            WORKSPACE_FIELD,
            PROJECT_PATH_FIELD,
            DIRECTORY_FIELD,
            TIMESTAMP_FIELD,
        )

        private val IGNORED_CONFIG_FILES = setOf(
            "settings.json",
            "plugins.json",
            "skills.json",
            "mcp_config.json",
            "hooks.json",
            "package.json",
            "tsconfig.json",
        )

        private val COMMON_GENERIC_FILENAMES = setOf(
            "transcript.jsonl",
            "transcript_full.jsonl",
            "metadata.json",
            "session.json",
            "conversation.json",
            "workspace.json",
        )

        private val WORKSPACE_MAPPING_REGEX = Regex("""([A-Za-z]:\\[^\r\n->]+|/[^\r\n->]+)\s+->""")
        private val CWD_JSON_REGEX = Regex(""""[Cc]wd"\s*:\s*(?:\\?"|")([^"\r\n]+)""")
        private val WORKSPACE_JSON_REGEX = Regex(""""workspace"\s*:\s*(?:\\?"|")([^"\r\n]+)""")
        private val SEARCH_DIR_REGEX = Regex(""""SearchDirectory"\s*:\s*(?:\\?"|")([^"\r\n]+)""")
        private val DIR_PATH_REGEX = Regex(""""DirectoryPath"\s*:\s*(?:\\?"|")([^"\r\n]+)""")
        private val SEARCH_PATH_REGEX = Regex(""""SearchPath"\s*:\s*(?:\\?"|")([^"\r\n]+)""")

        private val LOG = Logger.getLogger(AntigravityProjectProvider::class.java.name)

        private const val ARGS_FIELD = "args"
        private const val CONVERSATIONS_FIELD = "conversations"
        private const val WORKSPACE_URIS_FIELD = "WorkspaceURIs"
        private const val SUMMARY_ID_FIELD = "ID"
        private const val SUMMARY_TITLE_FIELD = "Title"
        private const val SUMMARY_PREVIEW_FIELD = "Preview"
        private const val SUMMARY_UPDATED_AT_FIELD = "UpdatedAt"
        private const val LAST_MODIFIED_FIELD = "last_modified_time"
        private const val CONVERSATION_METADATA_FILE = "conversation_metadata.json"
        private const val STEP_TYPE_FIELD = "type"
        private const val STEP_CONTENT_FIELD = "content"
        private const val USER_INPUT_TYPE = "USER_INPUT"
        private val STEP_FIELDS = setOf(STEP_TYPE_FIELD, STEP_CONTENT_FIELD)
        private val USER_INPUT_MARKERS = listOf("\"type\"")

        private fun cleanMatchedPath(raw: String): String =
            raw.trim()
                .removeSurrounding("\"")
                .removeSurrounding("\\\"")
                .replace("\\\\", "\\")
                .trimEnd('\\')
                .trim()

        private fun normalizeProjectPath(raw: String): String {
            var path = raw.trim()
            if (path.startsWith("file://", ignoreCase = true)) {
                path = path.substring(7)
                if (path.startsWith("/") && path.length >= 3 && path[2] == ':' && path[1].isLetter()) {
                    path = path.substring(1)
                }
            }
            return path
        }

        private fun defaultDataDirectory(): Path =
            EnvHomeDirectorySupport.resolveFirst("ANTIGRAVITY_DATA_DIR", "ANTIGRAVITY_HOME", "GEMINI_HOME") {
                AgentRuntime.userHome().resolve(".gemini").resolve("antigravity-cli")
            }
    }
}
