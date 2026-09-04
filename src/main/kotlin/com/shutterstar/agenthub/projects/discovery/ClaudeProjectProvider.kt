package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.latest
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.readBoundedLine
import com.shutterstar.agenthub.projects.model.ProjectComparators
import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.logging.Logger
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

class ClaudeProjectProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
    private val maxProjectDirectoryEntries: Int = MAX_PROJECT_DIRECTORY_ENTRIES,
    private val maxSessionEntries: Int = MAX_SESSION_ENTRIES,
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val projectsDirectory = homeDirectory.resolve(CLAUDE_DIRECTORY).resolve(PROJECTS_DIRECTORY)

    override fun isAvailable(): Boolean = Files.isDirectory(projectsDirectory, LinkOption.NOFOLLOW_LINKS)

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()

        var skippedSessions = 0
        var remainingSessionEntries = maxSessionEntries.coerceAtLeast(0)
        val sessions = mutableListOf<RawAgentProject>()
        Files.list(projectsDirectory).use { projectDirectories ->
            projectDirectories
                .limit(maxProjectDirectoryEntries.coerceAtLeast(0).toLong())
                .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted()
                .forEach { projectDirectory ->
                    if (remainingSessionEntries == 0) return@forEach
                    val result = discoverProjectDirectory(projectDirectory, remainingSessionEntries)
                    sessions += result.sessions
                    skippedSessions += result.skippedSessions
                    remainingSessionEntries -= result.scannedEntries
                }
        }
        if (skippedSessions > 0) {
            LOG.fine("[ProjectDiscovery] Claude: skipped $skippedSessions malformed or unreadable sessions")
        }
        return deduplicate(sessions)
    }

    private fun discoverProjectDirectory(projectDirectory: Path, entryLimit: Int): DirectoryDiscoveryResult {
        val sessions = mutableListOf<RawAgentProject>()
        var skippedSessions = 0
        var scannedEntries = 0
        return try {
            Files.list(projectDirectory).use { files ->
                files
                    .limit(entryLimit.coerceAtLeast(0).toLong())
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
            DirectoryDiscoveryResult(sessions, skippedSessions, scannedEntries)
        } catch (_: Exception) {
            DirectoryDiscoveryResult(emptyList(), skippedSessions = 1, scannedEntries = scannedEntries)
        }
    }

    private fun parseSession(sessionFile: Path): RawAgentProject? {
        var sessionId = sessionFile.nameWithoutExtension.takeIf { it.isNotBlank() }
        var projectPath: String? = null
        var startedAt: Instant? = null
        var sawTimestamp = false
        var remainingCharacters = MAX_HEADER_CHARACTERS
        var linesRead = 0

        Files.newBufferedReader(sessionFile).use { reader ->
            while (linesRead < MAX_HEADER_LINES && remainingCharacters > 0) {
                val line = readBoundedLine(reader, remainingCharacters, MAX_LINE_CHARACTERS) ?: break
                remainingCharacters -= line.charactersConsumed
                linesRead++
                val text = line.text ?: continue
                val fields = MetadataJsonParser.topLevelStringFields(text, METADATA_FIELDS)
                fields[SESSION_ID_FIELD]?.takeIf { it.isNotBlank() }?.let { sessionId = it }
                fields[WORKING_DIRECTORY_FIELD]?.takeIf { it.isNotBlank() }?.let { projectPath = it }
                fields[TIMESTAMP_FIELD]?.let { timestamp ->
                    sawTimestamp = true
                    startedAt = parseTimestamp(timestamp)
                }
                if (sessionId != null && projectPath != null && sawTimestamp) break
            }
        }

        val resolvedSessionId = sessionId ?: return null
        val resolvedProjectPath = projectPath ?: return null
        val fileModifiedAt = runCatching { Files.getLastModifiedTime(sessionFile, LinkOption.NOFOLLOW_LINKS) }
            .getOrNull()
            ?.toInstant()
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = resolvedProjectPath,
            sessionId = resolvedSessionId,
            startedAt = startedAt,
            updatedAt = latest(startedAt, fileModifiedAt),
            sourcePath = sessionFile.toAbsolutePath().normalize().toString(),
        )
    }

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

    private fun parseTimestamp(value: String): Instant? = try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }

    private data class DirectoryDiscoveryResult(
        val sessions: List<RawAgentProject>,
        val skippedSessions: Int,
        val scannedEntries: Int,
    )

    companion object {
        private const val AGENT_ID = "claude"
        private const val CLAUDE_DIRECTORY = ".claude"
        private const val PROJECTS_DIRECTORY = "projects"
        private const val JSONL_EXTENSION = "jsonl"
        private const val MAX_PROJECT_DIRECTORY_ENTRIES = 20_000
        private const val MAX_SESSION_ENTRIES = 50_000
        private const val MAX_HEADER_LINES = 100
        private const val MAX_HEADER_CHARACTERS = 512 * 1024
        private const val MAX_LINE_CHARACTERS = 64 * 1024
        private const val SESSION_ID_FIELD = "sessionId"
        private const val WORKING_DIRECTORY_FIELD = "cwd"
        private const val TIMESTAMP_FIELD = "timestamp"
        private val METADATA_FIELDS = setOf(SESSION_ID_FIELD, WORKING_DIRECTORY_FIELD, TIMESTAMP_FIELD)
        private val LOG = Logger.getLogger(ClaudeProjectProvider::class.java.name)
    }
}
