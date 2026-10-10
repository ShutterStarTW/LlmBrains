package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class WslDistroDiscoveryTest {
    @Test
    fun `should apply the timeout before reading and terminate a stalled process`() {
        val process = DistroProcess(completed = false)
        assertTrue(WslSupport.listDistros { process }.isEmpty())
        assertTrue(process.waited)
        assertTrue(process.destroyed)
        assertFalse(process.readBeforeWait)
    }

    @Test
    fun `should decode a completed distribution list`() {
        val process = DistroProcess(completed = true, bytes = "Ubuntu\nDebian\n".toByteArray())
        assertEquals(listOf("Ubuntu", "Debian"), WslSupport.listDistros { process })
        assertFalse(process.readBeforeWait)
    }

    @Test
    fun `should preserve interruption and terminate the process`() {
        val process = DistroProcess(completed = false, interrupt = true)
        try {
            assertTrue(WslSupport.listDistros { process }.isEmpty())
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(process.destroyed)
        } finally {
            Thread.interrupted()
        }
        assertTrue(WslSupport.listDistros { throw IOException("missing wsl") }.isEmpty())
    }

    private class DistroProcess(
        private val completed: Boolean,
        bytes: ByteArray = byteArrayOf(),
        private val interrupt: Boolean = false,
    ) : Process() {
        var waited = false
        var destroyed = false
        var readBeforeWait = false
        private val input = ByteArrayInputStream(bytes)
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream(): ByteArrayInputStream {
            if (!waited) {
                readBeforeWait = true
                error("output read before timeout")
            }
            return input
        }
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = error("unbounded wait")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waited = true
            if (interrupt) throw InterruptedException()
            return completed
        }
        override fun exitValue() = if (completed || destroyed) 0 else throw IllegalThreadStateException()
        override fun isAlive() = !completed && !destroyed
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
