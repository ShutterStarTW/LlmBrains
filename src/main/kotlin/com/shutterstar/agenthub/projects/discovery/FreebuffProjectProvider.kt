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
 * Freebuff chats (the Codebuff CLI; source `cli/src/utils/{config-dir,chat-history,chat-meta}.ts` and
 * `cli/src/project-files.ts`): `<config dir>/projects/<project folder name>/chats/<chatId>/` where the chat id is the
 * start time (`2026-10-05T18-07-03.513Z`) and the folder holds `chat-messages.json` (an array; a typed prompt has
 * `"variant":"user"`), the `chat-meta.json` sidecar (`messageCount`, `firstPrompt`, cut to 100 characters) and
 * `run-state.json`. The project folder name is only the *base name* of the project root, so the project path comes from
 * `run-state.json` (`sessionState.fileContext.projectRoot`, near the start of the file); a chat whose run state does not
 * name it is not listed rather than guessed. Chats without `chat-messages.json` or without a typed prompt are skipped.
 * The config dir is `~/.config/manicode`, or `FREEBUFF_CONFIG_DIR` (an absolute path).
 */
class FreebuffProjectProvider(
    private val configDirectory: Path = defaultConfigDirectory(),
) : AgentProjectProvider {
    override val agentId: String = AGENT_ID

    private val projectsDirectory = configDirectory.resolve(PROJECTS_DIRECTORY)

    override fun isAvailable(): Boolean = Files.isDirectory(projectsDirectory, LinkOption.NOFOLLOW_LINKS)

    override fun discover(): List<RawAgentProject> {
        if (!isAvailable()) return emptyList()
        val budget = ScanBudget(MAX_SCAN_ENTRIES)
        val sessions = mutableListOf<RawAgentProject>()
        var skipped = 0
        LocalSessionSupport.listDirectories(projectsDirectory, budget).forEach { projectDirectory ->
            LocalSessionSupport.listDirectories(projectDirectory.resolve(CHATS_DIRECTORY), budget).forEach { chatDirectory ->
                val messages = chatDirectory.resolve(MESSAGES_FILE)
                if (!Files.isRegularFile(messages, LinkOption.NOFOLLOW_LINKS)) return@forEach
                val session = runCatching { parseChat(chatDirectory, messages) }.getOrNull()
                if (session == null) skipped++ else sessions += session
            }
        }
        if (skipped > 0) LOG.fine("[ProjectDiscovery] Freebuff: skipped $skipped chats without a project path or typed prompt")
        return LocalSessionSupport.deduplicate(sessions)
    }

    private fun parseChat(chatDirectory: Path, messages: Path): RawAgentProject? {
        val chatId = chatDirectory.fileName.toString()
        val projectRoot = projectRoot(chatDirectory.resolve(RUN_STATE_FILE)) ?: return null
        val userMessages = countUserMessages(messages)
        if (userMessages == 0) return null
        // The sidecar is optional (chats from before it existed have none).
        val firstPrompt = chatDirectory.resolve(META_FILE).takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            ?.let { LocalSessionSupport.readBoundedText(it, MAX_META_CHARACTERS) }
            ?.let { MetadataJsonParser.topLevelStringFields(it, FIRST_PROMPT_ONLY)[FIRST_PROMPT_FIELD] }
            ?.takeIf { it.isNotBlank() && it != EMPTY_CHAT_PLACEHOLDER }
        val tally = UserMessageTally().apply {
            repeat(userMessages) { add(if (it == 0) firstPrompt else null) }
        }
        return RawAgentProject(
            agentId = agentId,
            rawProjectPath = projectRoot,
            sessionId = chatId,
            startedAt = chatStart(chatId),
            updatedAt = LocalSessionSupport.modifiedAt(messages),
            sourcePath = chatDirectory.toAbsolutePath().normalize().toString(),
            metadata = tally.metadata(),
        )
    }

    /** The first `"projectRoot"` string of the run state; it is the first key of `sessionState.fileContext`. */
    private fun projectRoot(runState: Path): String? {
        if (!Files.isRegularFile(runState, LinkOption.NOFOLLOW_LINKS)) return null
        val head = LocalSessionSupport.readBoundedText(runState, RUN_STATE_HEAD_CHARACTERS) ?: return null
        val raw = PROJECT_ROOT.find(head)?.groupValues?.get(1) ?: return null
        // Let the JSON parser unescape the captured literal (Windows paths are full of `\\`).
        return MetadataJsonParser.topLevelStringFields("{\"v\":\"$raw\"}", VALUE_ONLY)["v"]?.takeIf { it.isNotBlank() }
    }

    /** Typed prompts: occurrences of `"variant":"user"` (escaped copies inside message text never match). */
    private fun countUserMessages(messages: Path): Int {
        var count = 0
        Files.newBufferedReader(messages).use { reader ->
            val buffer = CharArray(READ_BUFFER)
            val window = StringBuilder()
            var total = 0
            while (total < MAX_MESSAGES_CHARACTERS) {
                val read = reader.read(buffer)
                if (read < 0) break
                total += read
                window.append(buffer, 0, read)
                var index = window.indexOf(USER_MARKER)
                var consumed = 0
                while (index >= 0) {
                    count++
                    consumed = index + USER_MARKER.length
                    index = window.indexOf(USER_MARKER, consumed)
                }
                // Keep a tail that may hold the start of a marker split across two reads.
                val keepFrom = maxOf(consumed, window.length - (USER_MARKER.length - 1))
                window.delete(0, keepFrom)
            }
        }
        return count
    }

    private fun chatStart(chatId: String): Instant? = CHAT_ID.matchEntire(chatId)?.let { match ->
        val (date, hour, minute, second, millis) = match.destructured
        runCatching { Instant.parse("${date}T$hour:$minute:$second.${millis}Z") }.getOrNull()
    }

    companion object {
        private const val AGENT_ID = "freebuff"
        private const val PROJECTS_DIRECTORY = "projects"
        private const val CHATS_DIRECTORY = "chats"
        private const val MESSAGES_FILE = "chat-messages.json"
        private const val META_FILE = "chat-meta.json"
        private const val RUN_STATE_FILE = "run-state.json"
        private const val MAX_SCAN_ENTRIES = 50_000
        private const val MAX_META_CHARACTERS = 16 * 1024
        private const val RUN_STATE_HEAD_CHARACTERS = 16 * 1024
        private const val MAX_MESSAGES_CHARACTERS = 64 * 1024 * 1024
        private const val READ_BUFFER = 64 * 1024
        private const val USER_MARKER = "\"variant\":\"user\""
        private const val FIRST_PROMPT_FIELD = "firstPrompt"
        private const val EMPTY_CHAT_PLACEHOLDER = "(empty chat)"
        private val FIRST_PROMPT_ONLY = setOf(FIRST_PROMPT_FIELD)
        private val VALUE_ONLY = setOf("v")
        private val PROJECT_ROOT = Regex("\"projectRoot\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        private val CHAT_ID = Regex("(\\d{4}-\\d{2}-\\d{2})T(\\d{2})-(\\d{2})-(\\d{2})\\.(\\d{3})Z")
        private val LOG: Logger = Logger.getLogger(FreebuffProjectProvider::class.java.name)

        private fun defaultConfigDirectory(): Path =
            EnvHomeDirectorySupport.resolveGuarded("FREEBUFF_CONFIG_DIR", AgentRuntime.userHome(), ".config/manicode")
    }
}
