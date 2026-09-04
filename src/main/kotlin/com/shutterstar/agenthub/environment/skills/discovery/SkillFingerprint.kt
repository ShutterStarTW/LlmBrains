package com.shutterstar.agenthub.environment.skills.discovery

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal class SkillFingerprint(
    private val maximumBytes: Long = DEFAULT_MAXIMUM_BYTES,
) {
    fun calculate(skillFile: Path): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = maximumBytes

        Files.newInputStream(skillFile).use { input ->
            while (remaining > 0) {
                val bytesRead = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (bytesRead < 0) {
                    break
                }
                digest.update(buffer, 0, bytesRead)
                remaining -= bytesRead
            }
        }

        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(Files.size(skillFile)).array())
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }.getOrNull()

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
        const val DEFAULT_MAXIMUM_BYTES = 512L * 1024L
    }
}
