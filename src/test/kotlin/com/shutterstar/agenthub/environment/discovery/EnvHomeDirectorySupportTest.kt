package com.shutterstar.agenthub.environment.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class EnvHomeDirectorySupportTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `should honor an agent home override for the default user home`() {
        val userHome = Path.of(System.getProperty("user.home"))

        val resolved = EnvHomeDirectorySupport.resolveGuarded(
            "KIRO_HOME",
            userHome,
            ".kiro",
            temporaryDirectory.toString(),
        )

        assertEquals(temporaryDirectory, resolved)
    }

    @Test
    fun `should keep injected homes isolated from host overrides`() {
        val resolved = EnvHomeDirectorySupport.resolveGuarded(
            "QWEN_HOME",
            temporaryDirectory,
            ".qwen",
            Path.of(System.getProperty("user.home")).toString(),
        )

        assertEquals(temporaryDirectory.resolve(".qwen"), resolved)
    }

    @Test
    fun `should expand home prefix in Qwen home override`() {
        val userHome = Path.of(System.getProperty("user.home"))

        val resolved = EnvHomeDirectorySupport.resolveGuarded("QWEN_HOME", userHome, ".qwen", "~/qwen-profile")

        assertEquals(userHome.resolve("qwen-profile"), resolved)
    }

    @Test
    fun `should append app name to an XDG base directory override`() {
        val userHome = Path.of(System.getProperty("user.home"))

        val resolved = EnvHomeDirectorySupport.resolveXdgGuarded(
            "XDG_CONFIG_HOME",
            userHome,
            ".config",
            "opencode",
            temporaryDirectory.toString(),
        )

        assertEquals(temporaryDirectory.resolve("opencode"), resolved)
    }

    @Test
    fun `should fall back to the default base directory when no XDG override is set`() {
        val userHome = Path.of(System.getProperty("user.home"))

        val resolved = EnvHomeDirectorySupport.resolveXdgGuarded(
            "XDG_CONFIG_HOME",
            userHome,
            ".config",
            "opencode",
            null,
        )

        assertEquals(userHome.resolve(".config").resolve("opencode"), resolved)
    }

    @Test
    fun `should keep injected homes isolated from an XDG override on the host`() {
        val resolved = EnvHomeDirectorySupport.resolveXdgGuarded(
            "XDG_CONFIG_HOME",
            temporaryDirectory,
            ".config",
            "opencode",
            "/some/host/xdg/config",
        )

        assertEquals(temporaryDirectory.resolve(".config").resolve("opencode"), resolved)
    }
}
