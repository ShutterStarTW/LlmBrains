package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.latest
import com.shutterstar.agenthub.projects.discovery.LocalSessionSupport.readBoundedLine
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

class CopilotProjectProvider(
    homeDirectory: Path = Path.of(System.getProperty("user.home")),
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val copilotDirectory = homeDirectory.resolve(COPILOT_DIRECTORY)
    private val projectsDirectory = copilotDirectory.resolve(SESSIONS_DIRECTORY)
    private val sessionStateDirectories = listOf(
        copilotDirectory.resolve(SESSION_STATE_DIRECTORY),
        copilotDirectory.resolve(HISTORY_SESSION_STATE_DIRECTORY),
    )

    override fun isAvailable(): Boolean =
        Files.isDirectory(projectsDirectory, LinkOption.NOFOLLOW_LINKS) ||
            sessionStateDirectories.any { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()

        var skippedSessions = 0
        val sessions = mutableListOf<RawAgentProject>()
        if (Files.isDirectory(projectsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            val scanBudget = ScanBudget()
            Files.list(projectsDirectory).use { projectDirectories ->
                projectDirectories
                    .limit(MAX_PROJECT_DIRECTORY_ENTRIES.toLong())
                    .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                    .sorted()
                    .takeWhile { scanBudget.remainingSessionEntries > 0 }
                    .forEach { projectDirectory ->
                        val result = discoverProjectDirectory(projectDirectory, scanBudget)
                        sessions += result.sessions
                        skippedSessions += result.skippedSessions
                    }
            }
        }
        sessionStateDirectories.forEach { directory ->
            val result = discoverSessionStateDirectory(directory)
            sessions += result.sessions
            skippedSessions += result.skippedSessions
        }
        if (skippedSessions > 0) {
            LOG.fine("[ProjectDiscovery] Copilot: skipped $skippedSessions malformed or unreadable sessions")
        }
        return deduplicate(sessions)
    }

    private fun discoverSessionStateDirectory(directory: Path): DirectoryDiscoveryResult {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return DirectoryDiscoveryResult(emptyList(), 0)
        return try {
            val sessions = mutableListOf<RawAgentProject>()
            var skippedSessions = 0
            Files.list(directory).use { entries ->
                entries
                    .limit(MAX_SESSION_STATE_ENTRIES.toLong())
                    .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                    .sorted()
                    .forEach { sessionDirectory ->
                        val session = runCatching { parseSessionState(sessionDirectory) }.getOrNull()
                        if (session == null) {
                            skippedSessions++
                        } else {
                            sessions += session
                        }
                    }
            }
            DirectoryDiscoveryResult(sessions, skippedSessions)
        } catch (_: Exception) {
            DirectoryDiscoveryResult(emptyList(), skippedSessions = 1)
        }
    }

    private fun parseSessionState(sessionDirectory: Path): RawAgentProject? {
        val sessionId = sessionDirectory.fileName?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val workspace = sessionDirectory.resolve(WORKSPACE_FILE)
        if (!Files.isRegularFile(workspace, LinkOption.NOFOLLOW_LINKS)) return null
        val projectPath = readYamlScalar(workspace, CWD_FIELD)
            ?: readYamlScalar(workspace, GIT_ROOT_FIELD)
            ?: return null
        val updatedAt = runCatching {
            Files.getLastModifiedTime(workspace, LinkOption.NOFOLLOW_LINKS).toInstant()
        }.getOrNull()
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = projectPath,
            sessionId = sessionId,
            startedAt = null,
            updatedAt = updatedAt,
            sourcePath = workspace.toAbsolutePath().normalize().toString(),
        )
    }

    private fun readYamlScalar(file: Path, key: String): String? {
        Files.newBufferedReader(file).useLines { lines ->
            return lines
                .take(MAX_WORKSPACE_LINES)
                .mapNotNull { line ->
                    val match = YAML_SCALAR.matchEntire(line) ?: return@mapNotNull null
                    if (match.groupValues[1] != key) return@mapNotNull null
                    match.groupValues[2].trim().trim('"', '\'').takeIf { it.isNotBlank() }
                }
                .firstOrNull()
        }
    }

    private fun discoverProjectDirectory(
        projectDirectory: Path,
        scanBudget: ScanBudget,
    ): DirectoryDiscoveryResult = try {
        val sessions = mutableListOf<RawAgentProject>()
        var skippedSessions = 0
        val entryLimit = minOf(MAX_SESSION_ENTRIES_PER_PROJECT, scanBudget.remainingSessionEntries)
        Files.list(projectDirectory).use { files ->
            files
                .limit(entryLimit.toLong())
                .peek { scanBudget.remainingSessionEntries-- }
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
        DirectoryDiscoveryResult(sessions, skippedSessions)
    } catch (_: Exception) {
        DirectoryDiscoveryResult(emptyList(), skippedSessions = 1)
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

    private fun parseTimestamp(value: String?): Instant? = try {
        value?.let(Instant::parse)
    } catch (_: DateTimeParseException) {
        null
    }

    private data class ScanBudget(
        var remainingSessionEntries: Int = MAX_TOTAL_SESSION_ENTRIES,
    )

    companion object {
        private const val AGENT_ID = "copilot"
        private const val COPILOT_DIRECTORY = ".copilot"
        private const val SESSIONS_DIRECTORY = "sessions"
        private const val SESSION_STATE_DIRECTORY = "session-state"
        private const val HISTORY_SESSION_STATE_DIRECTORY = "history-session-state"
        private const val WORKSPACE_FILE = "workspace.yaml"
        private const val CWD_FIELD = "cwd"
        private const val GIT_ROOT_FIELD = "git_root"
        private const val JSONL_EXTENSION = "jsonl"
        private const val MAX_HEADER_LINES = 32
        private const val MAX_HEADER_CHARACTERS = 512 * 1024
        private const val MAX_LINE_CHARACTERS = 256 * 1024
        private const val MAX_PROJECT_DIRECTORY_ENTRIES = 10_000
        private const val MAX_SESSION_ENTRIES_PER_PROJECT = 10_000
        private const val MAX_TOTAL_SESSION_ENTRIES = 50_000
        private const val MAX_SESSION_STATE_ENTRIES = 50_000
        private const val MAX_WORKSPACE_LINES = 128
        private const val SESSION_ID_FIELD = "id"
        private const val WORKING_DIRECTORY_FIELD = "cwd"
        private const val TIMESTAMP_FIELD = "timestamp"
        private val METADATA_FIELDS = setOf(SESSION_ID_FIELD, WORKING_DIRECTORY_FIELD, TIMESTAMP_FIELD)
        private val YAML_SCALAR = Regex("""^\s*([A-Za-z0-9_-]+)\s*:\s*(.*?)\s*$""")
        private val LOG = Logger.getLogger(CopilotProjectProvider::class.java.name)
    }
}

internal data class DirectoryDiscoveryResult(
    val sessions: List<RawAgentProject>,
    val skippedSessions: Int,
)
