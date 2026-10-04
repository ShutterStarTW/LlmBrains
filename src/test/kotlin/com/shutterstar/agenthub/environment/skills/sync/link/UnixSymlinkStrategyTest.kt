package com.shutterstar.agenthub.environment.skills.sync.link

import com.shutterstar.agenthub.OsDetector
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class UnixSymlinkStrategyTest {
    @TempDir
    lateinit var root: Path

    private val strategy = UnixSymlinkStrategy()

    @BeforeEach
    fun skipOnWindows() {
        assumeFalse(OsDetector.isWindows())
    }

    @Test
    fun `canLink is true for a real source directory and a non-existent target`() {
        val source = Files.createDirectory(root.resolve("source"))
        val target = root.resolve("target")

        assertTrue(strategy.canLink(source, target))
    }

    @Test
    fun `canLink remains true when an existing target will be replaced by the reviewed plan`() {
        val source = Files.createDirectory(root.resolve("source"))
        val target = Files.createDirectory(root.resolve("target"))

        assertTrue(strategy.canLink(source, target))
    }

    @Test
    fun `createLink creates a real symlink resolving to the source`() {
        val source = Files.createDirectory(root.resolve("source"))
        val target = root.resolve("target")

        val result = strategy.createLink(source, target)

        assertEquals(LinkResult.Success(EffectiveSyncMode.SYMLINK), result)
        assertTrue(Files.isSymbolicLink(target))
        assertEquals(source, Files.readSymbolicLink(target))
    }

    @Test
    fun `createLink fails when the target's parent directory does not exist`() {
        val source = Files.createDirectory(root.resolve("source"))
        val target = root.resolve("missing-parent").resolve("target")

        val result = strategy.createLink(source, target)

        assertTrue(result is LinkResult.Failure)
    }
}
