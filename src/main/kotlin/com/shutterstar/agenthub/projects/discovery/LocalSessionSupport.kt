package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.ScanBudget
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

    /** Character budget shared by every header scan (the first lines of a session file). */
    const val HEADER_CHARACTER_BUDGET = 512 * 1024

    /**
     * Feeds the first lines of [file] to [onLine] while staying inside a line-count and a character
     * budget. A line longer than [maxLineCharacters] is skipped (it still counts); [onLine] returns
     * true to stop early.
     */
    inline fun scanHeaderLines(file: Path, maxLines: Int, maxLineCharacters: Int, onLine: (String) -> Boolean) {
        Files.newBufferedReader(file).use { reader ->
            var remainingCharacters = HEADER_CHARACTER_BUDGET
            var linesRead = 0
            while (linesRead < maxLines && remainingCharacters > 0) {
                val line = readBoundedLine(reader, remainingCharacters, maxLineCharacters) ?: break
                remainingCharacters -= line.charactersConsumed
                linesRead++
                val text = line.text ?: continue
                if (onLine(text)) break
            }
        }
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

    /**
     * Keeps one record per session id: the most recently active one (ties go to the greater source
     * path, so the choice is deterministic), with metadata merged so the winner keeps what the other
     * copy knew (e.g. a first message found only in one of them).
     */
    fun deduplicate(sessions: List<RawAgentProject>): List<RawAgentProject> {
        val bySessionId = linkedMapOf<String, RawAgentProject>()
        sessions.forEach { candidate ->
            val existing = bySessionId[candidate.sessionId]
            if (existing == null || compareSessions(candidate, existing) > 0) {
                bySessionId[candidate.sessionId] = candidate.copy(
                    metadata = existing?.metadata.orEmpty() + candidate.metadata,
                    statistics = existing?.statistics.orEmpty() + candidate.statistics,
                )
            } else if (candidate.metadata.isNotEmpty() || candidate.statistics.isNotEmpty()) {
                bySessionId[candidate.sessionId] = existing.copy(
                    metadata = candidate.metadata + existing.metadata,
                    statistics = candidate.statistics + existing.statistics,
                )
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

    /** The newest timestamp [extract] finds in the last lines of [file] (most recent line first). */
    fun lastTimestamp(
        file: Path,
        maxTailBytes: Int,
        maxTailLines: Int,
        extract: (String) -> Instant?,
    ): Instant? = readTailLines(file, maxTailBytes, maxTailLines).asSequence().mapNotNull(extract).firstOrNull()

    /** Sorted child directories of [root], counted against [budget]; empty when [root] is not a directory. */
    fun listDirectories(root: Path, budget: ScanBudget): List<Path> {
        if (!budget.hasRemaining()) return emptyList()
        return listDirectories(root, budget.remaining()).also { budget.consume(it.size) }
    }

    /** Sorted child directories among the first [maximumEntries] entries of [root]. */
    fun listDirectories(root: Path, maximumEntries: Int): List<Path> {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.list(root).use { paths ->
            paths
                .limit(maximumEntries.coerceAtLeast(0).toLong())
                .toList()
                .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted()
        }
    }

    private const val EPOCH_MILLIS_THRESHOLD = 1_000_000_000_000L
}

/** Sessions found in one directory, plus how many entries could not be read. */
internal data class DirectoryDiscoveryResult(
    val sessions: List<RawAgentProject>,
    val skippedSessions: Int,
)
