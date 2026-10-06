package com.shutterstar.agenthub.environment.skills.sync.link

import com.shutterstar.agenthub.AgentRuntime
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Uses `mklink /J` rather than [Files.createSymbolicLink] because directory junctions need no
 * elevation/Developer Mode on Windows, unlike real symlinks.
 */
class WindowsJunctionStrategy(
    private val fileStoreIdentity: (Path) -> String? = { path ->
        runCatching { Files.getFileStore(path).let { "${it.name()}|${it.type()}" } }.getOrNull()
    },
) : FileLinkStrategy {
    override fun canLink(source: Path, target: Path): Boolean =
        AgentRuntime.isWindowsRuntime() &&
            Files.isDirectory(source) &&
            !source.toAbsolutePath().toString().startsWith("\\\\") &&
            !target.toAbsolutePath().toString().startsWith("\\\\") &&
            isSafeForCmd(source) &&
            isSafeForCmd(target) &&
            sameFileStore(source, target)

    /** Conservative preflight: cross-filesystem junctions are represented as reviewed copies. */
    private fun sameFileStore(source: Path, target: Path): Boolean {
        var existingTargetParent: Path? = target.toAbsolutePath().parent
        while (existingTargetParent != null && !Files.exists(existingTargetParent)) {
            existingTargetParent = existingTargetParent.parent
        }
        val sourceStore = fileStoreIdentity(source)
        val targetStore = existingTargetParent?.let(fileStoreIdentity)
        return sourceStore != null && sourceStore == targetStore
    }

    override fun createLink(source: Path, target: Path): LinkResult {
        if (!isSafeForCmd(source) || !isSafeForCmd(target)) {
            return LinkResult.Failure("Junction path contains characters unsafe for cmd.exe; use copy mode.")
        }
        var process: Process? = null
        return try {
            val command = "mklink /J ${quoteForCmd(target)} ${quoteForCmd(source)}"
            val runningProcess = ProcessBuilder(
                "cmd.exe",
                "/d",
                "/v:off",
                "/c",
                command,
            ).redirectErrorStream(true).start()
            process = runningProcess

            val finished = runningProcess.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                runningProcess.destroyForcibly()
                runningProcess.waitFor(PROCESS_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                return LinkResult.Failure("mklink timed out")
            }

            val output = runningProcess.inputStream.bufferedReader().use { it.readText() }
            if (runningProcess.exitValue() == 0 &&
                runCatching { Files.isSameFile(target, source) }.getOrDefault(false)
            ) {
                LinkResult.Success(EffectiveSyncMode.JUNCTION)
            } else {
                LinkResult.Failure(
                    output.ifBlank {
                        if (runningProcess.exitValue() == 0) {
                            "mklink returned success but did not create the requested junction"
                        } else {
                            "mklink failed with exit code ${runningProcess.exitValue()}"
                        }
                    },
                )
            }
        } catch (exception: InterruptedException) {
            process?.destroyForcibly()
            Thread.currentThread().interrupt()
            LinkResult.Failure("Interrupted while creating junction")
        } catch (exception: IOException) {
            LinkResult.Failure(exception.message ?: "Failed to create junction")
        }
    }

    private fun isSafeForCmd(path: Path): Boolean =
        path.toAbsolutePath().toString().none { it in CMD_META_CHARACTERS }

    private fun quoteForCmd(path: Path): String =
        "\"${path.toAbsolutePath()}\""

    private companion object {
        const val PROCESS_TIMEOUT_SECONDS = 15L
        const val PROCESS_TERMINATION_TIMEOUT_SECONDS = 5L
        const val CMD_META_CHARACTERS = "\"&|<>^()%!\r\n"
    }
}
