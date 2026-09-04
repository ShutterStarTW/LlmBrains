package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.epochTimestamp
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.latest
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.parseTimestamp
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.readBoundedLine
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.readBoundedText
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.readTailLines
import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.logging.Logger
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

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

        Files.walk(directory, MAX_SCAN_DEPTH).use { paths ->
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
        var remainingCharacters = MAX_HEADER_CHARACTERS
        var linesRead = 0

        Files.newBufferedReader(sessionFile).use { reader ->
            while (linesRead < MAX_HEADER_LINES && remainingCharacters > 0) {
                val line = readBoundedLine(reader, remainingCharacters, MAX_LINE_CHARACTERS) ?: break
                remainingCharacters -= line.charactersConsumed
                linesRead++
                val text = line.text ?: continue

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

                if (sessionId != null && projectPath != null && startedAt != null) {
                    break
                }
            }
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

        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = normalizeProjectPath(resolvedProjectPath),
            sessionId = resolvedSessionId,
            startedAt = startedAt,
            updatedAt = latest(startedAt, lastEventAt, fileModifiedAt),
            sourcePath = sessionFile.toAbsolutePath().normalize().toString(),
            metadata = metadata,
        )
    }

    private fun findLastEventTimestamp(sessionFile: Path): Instant? =
        readTailLines(sessionFile, MAX_TAIL_BYTES, MAX_TAIL_LINES)
            .asSequence()
            .mapNotNull { line ->
                val fields = MetadataJsonParser.topLevelStringFields(line, TIMESTAMP_FIELDS)
                fields[CREATED_AT_FIELD]
                    ?: fields[CREATED_AT_CAMEL_FIELD]
                    ?: fields[TIMESTAMP_FIELD]
                    ?: fields[TIME_FIELD]
                    ?: fields[UPDATED_AT_FIELD]
                    ?: fields[UPDATED_AT_CAMEL_FIELD]
            }
            .mapNotNull(::parseTimestamp)
            .firstOrNull()

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
        if (!text.contains(CONVERSATIONS_FIELD)) return emptyList()

        val results = mutableListOf<RawAgentProject>()
        val convRegex = Regex(
            """"([a-zA-Z0-9_-]+)"\s*:\s*\{[^{}]*"summary"\s*:\s*\{([^}]+)\}""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val fileModifiedAt = runCatching { Files.getLastModifiedTime(sessionFile, LinkOption.NOFOLLOW_LINKS) }
            .getOrNull()
            ?.toInstant()

        convRegex.findAll(text).forEach { match ->
            val convId = match.groupValues[1]
            val summaryBody = match.groupValues[2]

            val uriMatch = Regex(""""WorkspaceURIs"\s*:\s*\[\s*"([^"]+)"""").find(summaryBody)
            val rawPath = uriMatch?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return@forEach
            val projectPath = normalizeProjectPath(rawPath)

            val idMatch = Regex(""""ID"\s*:\s*"([^"]+)"""").find(summaryBody)
            val sessionId = idMatch?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: convId

            val titleMatch = Regex(""""Title"\s*:\s*"([^"]+)"""").find(summaryBody)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            val previewMatch = Regex(""""Preview"\s*:\s*"([^"]+)"""").find(summaryBody)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            val title = titleMatch ?: previewMatch

            val updatedAtMatch = Regex(""""UpdatedAt"\s*:\s*"([^"]+)"""").find(summaryBody)?.groupValues?.get(1)
            val lastModifiedMatch = Regex(""""last_modified_time"\s*:\s*"([^"]+)"""").find(match.value)?.groupValues?.get(1)
            val updatedAt = parseTimestamp(updatedAtMatch) ?: parseTimestamp(lastModifiedMatch)

            val metadata = mutableMapOf<String, String>()
            if (!title.isNullOrBlank()) {
                metadata[TITLE_FIELD] = title
            }

            results.add(
                RawAgentProject(
                    agentId = agentId,
                    rawProjectPath = projectPath,
                    sessionId = sessionId,
                    startedAt = null,
                    updatedAt = updatedAt ?: fileModifiedAt,
                    sourcePath = sessionFile.toAbsolutePath().normalize().toString(),
                    metadata = metadata,
                ),
            )
        }
        return results
    }

    private data class DirectoryDiscoveryResult(
        val sessions: List<RawAgentProject>,
        val skippedSessions: Int,
    )

    companion object {
        private const val AGENT_ID = "antigravity"
        private const val SCRATCH_DIRECTORY = "scratch"
        private const val JSONL_EXTENSION = "jsonl"
        private const val JSON_EXTENSION = "json"

        private const val MAX_SCAN_DEPTH = 5
        private const val MAX_SCAN_ENTRIES = 50_000
        private const val MAX_HEADER_LINES = 100
        private const val MAX_HEADER_CHARACTERS = 512 * 1024
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
        private const val CONVERSATION_METADATA_FILE = "conversation_metadata.json"

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

        private fun defaultDataDirectory(): Path {
            val configured = System.getenv("ANTIGRAVITY_DATA_DIR")?.trim()?.takeIf { it.isNotEmpty() }
                ?: System.getenv("ANTIGRAVITY_HOME")?.trim()?.takeIf { it.isNotEmpty() }
                ?: System.getenv("GEMINI_HOME")?.trim()?.takeIf { it.isNotEmpty() }
            return configured?.let { runCatching { Path.of(it) }.getOrNull() }
                ?: Path.of(System.getProperty("user.home"), ".gemini", "antigravity-cli")
        }
    }
}
