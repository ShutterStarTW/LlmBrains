package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class UpdateScriptsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `should execute fallback chains quotes and pipes and report per agent results`() {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val bash = if (windows) Path.of("C:/Program Files/Git/bin/bash.exe") else Path.of("/bin/bash")
        assumeTrue(Files.isExecutable(bash))
        val script = directory.resolve("llmbrains.sh")
        Files.copy(Path.of("src/main/resources/scripts/llmbrains.sh"), script)
        script.toFile().setExecutable(true)
        val definitions = directory.resolve("agents.txt")
        Files.writeString(definitions, listOf(
            "fallback|Fallback|bash|--version|"+encodeAgentScriptField("false || printf '%s' 'quoted value' | grep -q 'quoted value'")+"|",
            "current|Current|bash|--version|printf 'already installed'|",
            "failed|Failed|bash|--version|printf 'Successfully installed partial'; exit 1|",
        ).joinToString("~"))
        val report = directory.resolve("report.txt")
        val output = directory.resolve("output.txt")
        val process = ProcessBuilder(
            bash.toString(), script.toString(), "update-all", definitions.toString(),
            "fallback,current,failed", report.toString(),
        ).redirectErrorStream(true).redirectOutput(output.toFile()).apply {
            environment()["TERM"] = "dumb"
            environment().remove("WSL_DISTRO_NAME")
            environment().remove("WSL_INTEROP")
        }.start()
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Mock update script timed out")
            assertEquals(0, process.exitValue(), Files.readString(output))
            val parsed = UpdateAllReport.parse(Files.readString(report))
            assertEquals(setOf("fallback"), parsed.updatedIds)
            assertEquals(setOf("current"), parsed.upToDateIds)
            assertEquals(setOf("failed"), parsed.failedIds)
            assertEquals(listOf("failed"), parsed.remainingOutdated(
                setOf("fallback", "current", "failed"), setOf("fallback", "current", "failed"),
            ))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `should report successful current and failed PowerShell updates`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val definitions = directory.resolve("agents.txt")
        val rows = listOf(
            listOf("fallback", "Fallback", "cmd", "", "cmd /c exit 1 || echo fallback", ""),
            listOf("current", "Current", "cmd", "", "echo already installed", ""),
            listOf("failed", "Failed", "cmd", "", "echo Successfully installed partial & exit 1", ""),
        )
        Files.writeString(definitions, rows.joinToString("~") { row ->
            row.joinToString("|", transform = ::encodeAgentScriptField)
        })
        val report = directory.resolve("report.txt")
        val output = directory.resolve("output.txt")
        val process = ProcessBuilder(
            "powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
            Path.of("src/main/resources/scripts/llmbrains.ps1").toAbsolutePath().toString(),
            "update-all", definitions.toString(), "fallback,current,failed", report.toString(),
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Mock PowerShell update timed out")
            assertEquals(0, process.exitValue(), Files.readString(output))
            val parsed = UpdateAllReport.parse(Files.readString(report))
            assertEquals(setOf("fallback"), parsed.updatedIds)
            assertEquals(setOf("current"), parsed.upToDateIds)
            assertEquals(setOf("failed"), parsed.failedIds)
        } finally {
            process.destroyForcibly()
        }
    }
}
