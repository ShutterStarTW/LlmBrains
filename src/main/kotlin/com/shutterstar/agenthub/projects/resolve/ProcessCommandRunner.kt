package com.shutterstar.agenthub.projects.resolve

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object ProcessCommandRunner {
    private const val OUTPUT_DRAIN_TIMEOUT_MILLIS = 2_000L

    fun run(command: List<String>, timeoutMillis: Long): String? {
        var process: Process? = null
        return try {
            val started = ProcessBuilder(command).redirectErrorStream(true).start()
            process = started
            val outputFuture = CompletableFuture.supplyAsync {
                started.inputStream.bufferedReader().use { it.readText() }
            }
            if (!started.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                started.destroyForcibly()
                started.waitFor(OUTPUT_DRAIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                outputFuture.cancel(true)
                null
            } else {
                readOutput(outputFuture).takeIf { started.exitValue() == 0 }
            }
        } catch (_: IOException) {
            process?.destroyForcibly()
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process?.destroyForcibly()
            null
        }
    }

    private fun readOutput(outputFuture: CompletableFuture<String>): String? = try {
        outputFuture.get(OUTPUT_DRAIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: TimeoutException) {
        null
    } catch (_: ExecutionException) {
        null
    }
}
