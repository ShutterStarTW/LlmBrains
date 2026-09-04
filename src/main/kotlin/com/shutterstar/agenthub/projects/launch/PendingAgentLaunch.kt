package com.shutterstar.agenthub.projects.launch

import com.shutterstar.agenthub.projects.discovery.MetadataJsonParser
import com.shutterstar.agenthub.projects.resolve.ProjectResolver
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Logger

/**
 * Cross-process handoff for "open this project in another JetBrains IDE, then launch an agent
 * there". [TerminalCommandRunner] can only attach to a [com.intellij.openapi.project.Project]
 * already loaded in the current process, so it cannot reach into a JetBrains IDE started as a
 * separate OS process. Instead, the launching side writes this one-shot request to a file the
 * *target* IDE's own AgentHub instance reads back once it opens the matching project — see
 * [com.shutterstar.agenthub.LlmBrainsStartupActivity]. If AgentHub isn't installed/enabled in the
 * target IDE, the request is simply never consumed and expires.
 */
private data class PendingAgentLaunch(
    val projectPath: String,
    val agentId: String,
    val requestedAtEpochMillis: Long,
)

class PendingAgentLaunchStore(
    private val storeFile: Path = defaultStoreFile(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun request(projectDirectory: Path, agentId: String) {
        val canonicalPath = ProjectResolver.normalizeFilesystemPath(projectDirectory.toString()) ?: return
        runCatching {
            val parent = storeFile.parent ?: return
            Files.createDirectories(parent)
            val json = "{" +
                "\"projectPath\":${jsonString(canonicalPath)}," +
                "\"agentId\":${jsonString(agentId)}," +
                "\"requestedAtEpochMillis\":${now()}" +
                "}"
            val tempFile = Files.createTempFile(parent, "pending-launch", ".tmp")
            Files.writeString(tempFile, json)
            Files.move(tempFile, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { error ->
            LOG.warning("[PendingAgentLaunch] Could not write handoff request: ${error.javaClass.simpleName}")
        }
    }

    /**
     * Consumes a pending request only when it is invalid, expired, or matches [openedProjectPath].
     * A fresh request for another project is preserved for that project's startup activity.
     */
    fun consumeIfMatching(openedProjectPath: String?): String? {
        if (!Files.isRegularFile(storeFile)) return null
        val json = runCatching { Files.readString(storeFile) }.getOrNull()
        val pending = json?.let(::parse)
        if (pending == null) {
            deleteStoreFile()
            return null
        }
        if (now() - pending.requestedAtEpochMillis > MAX_AGE_MILLIS) {
            deleteStoreFile()
            return null
        }
        val openedCanonicalPath = openedProjectPath?.let { ProjectResolver.normalizeFilesystemPath(it) } ?: return null
        if (pending.projectPath != openedCanonicalPath) return null
        deleteStoreFile()
        return pending.agentId
    }

    private fun deleteStoreFile() {
        runCatching { Files.deleteIfExists(storeFile) }
    }

    private fun parse(json: String): PendingAgentLaunch? {
        val fields = MetadataJsonParser.topLevelStringFields(json, REQUIRED_FIELDS)
        val projectPath = fields[FIELD_PROJECT_PATH]?.takeIf(String::isNotBlank) ?: return null
        val agentId = fields[FIELD_AGENT_ID]?.takeIf(String::isNotBlank) ?: return null
        val requestedAt = MetadataJsonParser.topLevelLongFields(json, setOf(FIELD_REQUESTED_AT))[FIELD_REQUESTED_AT] ?: return null
        return PendingAgentLaunch(projectPath, agentId, requestedAt)
    }

    private fun jsonString(value: String): String {
        val escaped = buildString {
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
                }
            }
        }
        return "\"$escaped\""
    }

    companion object {
        private const val FIELD_PROJECT_PATH = "projectPath"
        private const val FIELD_AGENT_ID = "agentId"
        private const val FIELD_REQUESTED_AT = "requestedAtEpochMillis"
        private val REQUIRED_FIELDS = setOf(FIELD_PROJECT_PATH, FIELD_AGENT_ID)
        private const val MAX_AGE_MILLIS = 5 * 60 * 1000L
        private val LOG = Logger.getLogger(PendingAgentLaunchStore::class.java.name)

        private fun defaultStoreFile(): Path =
            Path.of(System.getProperty("user.home"), ".agenthub", "pending-launch.json")
    }
}
