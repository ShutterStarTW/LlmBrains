package com.shutterstar.agenthub.projects.ide

import com.shutterstar.agenthub.OsDetector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JetBrainsIdeDetectorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should detect current and external IDE installations from product metadata`() {
        val idea = createInstallation("IntelliJ IDEA", "IU", "idea64.exe")
        val phpStorm = createInstallation("PhpStorm", "PS", "phpstorm64.exe", tempDir.resolve("nested/toolbox"))
        val detector = JetBrainsIdeDetector(
            environment = emptyMap(),
            userHome = tempDir,
            currentHome = idea,
            osType = OsDetector.OsType.WINDOWS,
        )

        val installations = detector.detectIn(listOf(tempDir), currentHome = idea)

        assertEquals(listOf(JetBrainsIdeProduct.INTELLIJ_IDEA, JetBrainsIdeProduct.PHPSTORM), installations.map { it.product })
        assertTrue(installations.first().isCurrent)
        assertFalse(installations.last().isCurrent)
        assertEquals(phpStorm, installations.last().home)
    }

    @Test
    fun `should ignore Toolbox Windows shell-script shims that are not native executables`() {
        val current = tempDir.resolve("current").also(Files::createDirectories)
        val scriptsDir = tempDir.resolve("scripts").also(Files::createDirectories)
        // JetBrains Toolbox on Windows ships an extension-less "pycharm" shell-script shim on
        // PATH; ProcessBuilder cannot execute it directly (CreateProcess error 193).
        Files.createFile(scriptsDir.resolve("pycharm"))
        val detector = JetBrainsIdeDetector(emptyMap(), tempDir, current, OsDetector.OsType.WINDOWS)

        val installations = detector.detectIn(roots = emptyList(), pathEntries = listOf(scriptsDir), currentHome = current)

        assertTrue(installations.none { it.product == JetBrainsIdeProduct.PYCHARM })
    }

    @Test
    fun `should detect a real Windows exe launcher on PATH alongside a Toolbox shim`() {
        val current = tempDir.resolve("current").also(Files::createDirectories)
        val scriptsDir = tempDir.resolve("scripts").also(Files::createDirectories)
        Files.createFile(scriptsDir.resolve("pycharm"))
        Files.createFile(scriptsDir.resolve("pycharm64.exe"))
        val detector = JetBrainsIdeDetector(emptyMap(), tempDir, current, OsDetector.OsType.WINDOWS)

        val installations = detector.detectIn(roots = emptyList(), pathEntries = listOf(scriptsDir), currentHome = current)

        val pycharm = installations.singleOrNull { it.product == JetBrainsIdeProduct.PYCHARM }
        assertEquals(scriptsDir.resolve("pycharm64.exe"), pycharm?.launcher)
    }

    @Test
    fun `should ignore unknown or incomplete product metadata`() {
        val current = tempDir.resolve("current").also(Files::createDirectories)
        Files.writeString(current.resolve("product-info.json"), """{"name":"Unknown","productCode":"XX"}""")
        val broken = tempDir.resolve("broken").also(Files::createDirectories)
        Files.writeString(broken.resolve("product-info.json"), """{"name":"WebStorm","productCode":"WS"}""")
        val detector = JetBrainsIdeDetector(emptyMap(), tempDir, current, OsDetector.OsType.WINDOWS)

        assertTrue(detector.detectIn(listOf(tempDir), currentHome = current).isEmpty())
    }

    private fun createInstallation(
        name: String,
        productCode: String,
        launcherName: String,
        parent: Path = tempDir,
    ): Path {
        val home = parent.resolve(name.replace(" ", ""))
        Files.createDirectories(home.resolve("bin"))
        Files.writeString(home.resolve("product-info.json"), """{"name":"$name","productCode":"$productCode"}""")
        Files.createFile(home.resolve("bin").resolve(launcherName))
        return home
    }
}
