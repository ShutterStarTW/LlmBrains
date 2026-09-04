package com.shutterstar.agenthub.projects.ide

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JetBrainsIdeLauncherTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should pass launcher and project as separate process arguments`() {
        val launcher = Files.createFile(tempDir.resolve("idea64.exe"))
        val installation = installation(launcher)
        var capturedCommand = emptyList<String>()

        val result = JetBrainsIdeLauncher.launch(installation, tempDir) { command -> capturedCommand = command }

        assertTrue(result.isSuccess)
        assertEquals(listOf(launcher.toString(), tempDir.toString()), capturedCommand)
    }

    @Test
    fun `should report a missing launcher without starting a process`() {
        val installation = installation(tempDir.resolve("missing.exe"))
        var started = false

        val result = JetBrainsIdeLauncher.launch(installation, tempDir) { started = true }

        assertTrue(result.isFailure)
        assertEquals(false, started)
    }

    private fun installation(launcher: Path) = JetBrainsIdeInstallation(
        product = JetBrainsIdeProduct.INTELLIJ_IDEA,
        name = "IntelliJ IDEA",
        home = tempDir,
        launcher = launcher,
        isCurrent = false,
    )
}
