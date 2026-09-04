package com.shutterstar.agenthub.projects.resolve

import com.shutterstar.agenthub.OsDetector
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration

class ProcessCommandRunnerTest {
    @Test
    fun `captures output of a short-lived process`() {
        val echoCommand = if (OsDetector.isWindows()) {
            listOf("cmd", "/c", "echo hello-agenthub")
        } else {
            listOf("sh", "-c", "echo hello-agenthub")
        }
        val output = ProcessCommandRunner.run(echoCommand, timeoutMillis = 5_000)

        assertTrue(output?.contains("hello-agenthub") == true, "expected output to contain the echoed text, was: $output")
    }

    @Test
    fun `a process that never finishes is force-killed within the timeout instead of leaking`() {
        val sleepCommand = if (OsDetector.isWindows()) {
            listOf("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 30")
        } else {
            listOf("sh", "-c", "sleep 30")
        }
        val result = assertTimeout(
            Duration.ofSeconds(3),
            ThrowingSupplier { ProcessCommandRunner.run(sleepCommand, timeoutMillis = 300) },
        )

        assertNull(result)
    }

    @Test
    fun `an invalid executable returns null instead of throwing`() {
        val output = ProcessCommandRunner.run(
            listOf("this-executable-does-not-exist-agenthub"),
            timeoutMillis = 1_000,
        )

        assertNull(output)
    }
}
