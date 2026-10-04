package com.shutterstar.agenthub.projects.discovery

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Counts the messages the *user* typed in one session and remembers the first one as a title.
 * Every provider decides for itself which transcript records are real user input; this only
 * accumulates and turns the result into [RawAgentProject][com.shutterstar.agenthub.projects.model.RawAgentProject]
 * metadata.
 */
internal class UserMessageTally {
    var count: Int = 0
        private set
    var firstMessage: String? = null
        private set

    fun add(text: String?) {
        count++
        if (firstMessage == null) firstMessage = MessageContentExtractor.titleText(text)
    }

    fun metadata(): Map<String, String> = buildMap {
        put(MESSAGE_COUNT_KEY, count.toString())
        firstMessage?.let { put(FIRST_MESSAGE_KEY, it) }
    }

    companion object {
        const val FIRST_MESSAGE_KEY = "firstMessage"
        const val MESSAGE_COUNT_KEY = "messageCount"
        private const val MAX_LINES = 1_000_000

        /**
         * Feeds every line of a JSONL transcript that contains one of [markers] to [onLine]. The
         * markers are a cheap substring pre-filter so the (much more expensive) JSON field parsing
         * only runs on candidate lines of what can be a very large file. Null when the file is
         * missing or unreadable.
         */
        fun scanJsonl(file: Path, markers: List<String>, onLine: UserMessageTally.(String) -> Unit): UserMessageTally? {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
            return runCatching {
                val tally = UserMessageTally()
                Files.newBufferedReader(file).use { reader ->
                    var linesRead = 0
                    while (linesRead < MAX_LINES) {
                        val line = reader.readLine() ?: break
                        linesRead++
                        if (markers.any(line::contains)) tally.onLine(line)
                    }
                }
                tally
            }.getOrNull()
        }
    }
}
