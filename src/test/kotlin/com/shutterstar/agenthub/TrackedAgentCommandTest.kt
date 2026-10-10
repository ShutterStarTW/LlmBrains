package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TrackedAgentCommandTest {
    @TempDir lateinit var directory: Path

    @Test fun `background completion waits for process exit after draining output`() {
        val waiting = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val output = ByteArrayInputStream(ByteArray(100_000))
        val process = object : Process() {
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream() = output
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun waitFor(): Int { waiting.countDown(); finished.await(); return 7 }
            override fun exitValue(): Int = throw IllegalThreadStateException()
            override fun destroy() = Unit
        }
        val future = CompletableFuture.supplyAsync { runBackgroundProcess(listOf("fake")) { process } }
        try {
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            assertEquals(0, output.available())
            assertFalse(future.isDone)
        } finally { finished.countDown() }
        assertEquals(7, future.get(5, TimeUnit.SECONDS))
    }


    @Test
    fun `should complete a detached process that writes more than a pipe buffer`() {
        val argv = if (OsDetector.isWindows()) {
            listOf("powershell", "-NoProfile", "-NonInteractive", "-Command",
                "[Console]::Out.Write(('x' * 2097152)); [Console]::Error.Write(('y' * 2097152))")
        } else {
            listOf("bash", "-c", "head -c 2097152 /dev/zero; head -c 2097152 /dev/zero >&2")
        }
        val process = startDetachedBackgroundProcess(argv)
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "a discarded output stream cannot fill a pipe")
            assertEquals(0, process.exitValue())
            assertEquals(-1, process.inputStream.read())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test fun `background launch failure and interruption release the operation`() {
        assertEquals(-1, runBackgroundProcess(listOf("fake")) { throw IOException("missing") })
        try {
            assertEquals(-1, runBackgroundProcess(listOf("fake")) { throw InterruptedException() })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `terminal PowerShell script reports actual exit code with quoted paths`() {
        if (!OsDetector.isWindows()) return
        val result = directory.resolve("result's code")
        val script = directory.resolve("run.ps1")
        Files.writeString(script, trackedCommandScript("Write-Output 'test output'; & cmd.exe /c exit 7", result.toString(), true))
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString())
            .redirectErrorStream(true).start()
        process.inputStream.use { it.readBytes() }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(7, process.exitValue())
        assertEquals("7", Files.readString(result))
    }

    @Test fun `terminal PowerShell script reports errors instead of success`() {
        if (!OsDetector.isWindows()) return
        val result = directory.resolve("exit-code")
        val script = directory.resolve("run.ps1")
        Files.writeString(script, trackedCommandScript("throw 'test failure'", result.toString(), true))
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString())
            .redirectErrorStream(true).start()
        process.inputStream.use { it.readBytes() }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals("1", Files.readString(result))
        assertTrue(process.exitValue() != 0)
    }

    @Test fun `shell completion isolates command exit and escapes apostrophes`() {
        val script = trackedCommandScript("exit 7", "/tmp/agent's result", false)
        assertTrue(script.contains("(\nexit 7\n)"))
        assertTrue(script.contains("'/tmp/agent'\\''s result'"))
        assertTrue(script.contains("agenthub_exit_code=$?"))
    }
}
