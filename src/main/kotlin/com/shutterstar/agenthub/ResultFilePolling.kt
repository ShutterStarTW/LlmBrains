package com.shutterstar.agenthub

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The timeout applies to incomplete nonempty files and failed reads as well as missing files. */
internal class ResultFilePolling(
    private val file: Path,
    timeoutMillis: Long,
    private val now: () -> Long = System::nanoTime,
) {
    internal sealed interface Outcome {
        data object Pending : Outcome
        data object TimedOut : Outcome
        data class Complete(val content: String) : Outcome
    }

    private val started = now()
    private val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

    fun poll(): Outcome {
        if (now() - started >= timeoutNanos) return Outcome.TimedOut
        return try {
            if (!Files.isRegularFile(file)) return Outcome.Pending
            val content = Files.readString(file)
            if (content.lineSequence().any { it.trim() == "done=1" }) Outcome.Complete(content) else Outcome.Pending
        } catch (_: IOException) {
            Outcome.Pending
        }
    }
}
