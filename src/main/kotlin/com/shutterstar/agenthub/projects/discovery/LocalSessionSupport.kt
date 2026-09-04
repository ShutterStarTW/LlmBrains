package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.model.ProjectComparators
import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.io.BufferedReader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.OffsetDateTime

internal object LocalSessionSupport {
    fun readBoundedText(file: Path, maxCharacters: Int): String? = Files.newBufferedReader(file).use { reader ->
        val text = StringBuilder()
        val buffer = CharArray(8 * 1024)
        while (text.length < maxCharacters) {
            val count = reader.read(buffer, 0, minOf(buffer.size, maxCharacters - text.length))
            if (count < 0) break
            text.append(buffer, 0, count)
        }
        text.toString().takeIf { it.isNotBlank() }
    }

    data class BoundedLine(
        val text: String?,
        val charactersConsumed: Int,
    )

    fun readBoundedLine(reader: BufferedReader, characterBudget: Int, maxLineCharacters: Int): BoundedLine? {
        val line = StringBuilder(minOf(characterBudget, maxLineCharacters))
        var consumed = 0
        var truncated = false
        while (consumed < characterBudget) {
            val character = reader.read()
            if (character < 0) {
                return if (consumed == 0) null else BoundedLine(line.takeUnless { truncated }?.toString(), consumed)
            }
            consumed++
            if (character.toChar() == '\n') break
            if (character.toChar() != '\r') {
                if (line.length < maxLineCharacters) {
                    line.append(character.toChar())
                } else {
                    truncated = true
                }
            }
        }
        return BoundedLine(line.takeUnless { truncated }?.toString(), consumed)
    }

    /**
     * Reads up to [maxTailBytes] from the end of [file], decodes it as UTF-8 (dropping a
     * possibly-partial first line), and returns up to [maxTailLines] non-blank lines ordered
     * most-recent-first.
     */
    fun readTailLines(file: Path, maxTailBytes: Int, maxTailLines: Int): List<String> {
        val tail = Files.newByteChannel(file, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            val bytesToRead = minOf(size, maxTailBytes.toLong()).toInt()
            val start = size - bytesToRead
            channel.position(start)
            val buffer = ByteBuffer.allocate(bytesToRead)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Continue until the requested tail has been read or EOF is reached.
            }
            buffer.flip()
            val decoded = StandardCharsets.UTF_8.decode(buffer).toString()
            if (start > 0) decoded.substringAfter('\n', "") else decoded
        }
        return tail.lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .asReversed()
            .take(maxTailLines)
    }

    fun modifiedAt(file: Path): Instant? = runCatching {
        Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant()
    }.getOrNull()

    fun parseTimestamp(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: value.toLongOrNull()?.let(::epochTimestamp)
    }

    fun epochTimestamp(value: Long?): Instant? = value?.let {
        runCatching {
            if (it > EPOCH_MILLIS_THRESHOLD) Instant.ofEpochMilli(it) else Instant.ofEpochSecond(it)
        }.getOrNull()
    }

    fun latest(vararg values: Instant?): Instant? = values.filterNotNull().maxOrNull()

    fun deduplicate(sessions: List<RawAgentProject>): List<RawAgentProject> {
        val bySessionId = linkedMapOf<String, RawAgentProject>()
        sessions.forEach { candidate ->
            val existing = bySessionId[candidate.sessionId]
            val candidateActivity = candidate.updatedAt ?: candidate.startedAt ?: Instant.MIN
            val existingActivity = existing?.updatedAt ?: existing?.startedAt ?: Instant.MIN
            if (existing == null || candidateActivity > existingActivity) {
                bySessionId[candidate.sessionId] = candidate.copy(
                    metadata = existing?.metadata.orEmpty() + candidate.metadata,
                )
            } else if (candidate.metadata.isNotEmpty()) {
                bySessionId[candidate.sessionId] = existing.copy(
                    metadata = candidate.metadata + existing.metadata,
                )
            }
        }
        return bySessionId.values.sortedWith(ProjectComparators.rawAgentProjectByRecency)
    }

    private const val EPOCH_MILLIS_THRESHOLD = 1_000_000_000_000L
}
