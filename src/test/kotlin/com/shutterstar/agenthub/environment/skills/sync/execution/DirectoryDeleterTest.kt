package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.OsDetector
import com.shutterstar.agenthub.environment.skills.sync.link.LinkResult
import com.shutterstar.agenthub.environment.skills.sync.link.WindowsJunctionStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DirectoryDeleterTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `recursive delete removes a nested junction without deleting its target`() {
        assumeTrue(OsDetector.isWindows())
        val outside = Files.createDirectory(root.resolve("outside"))
        val sentinel = Files.writeString(outside.resolve("sentinel.txt"), "keep")
        val skill = Files.createDirectory(root.resolve("skill"))
        Files.writeString(skill.resolve("SKILL.md"), "content")
        val junction = skill.resolve("assets")
        assumeTrue(
            WindowsJunctionStrategy().createLink(outside, junction) is LinkResult.Success,
            "junction creation is unavailable",
        )

        DirectoryDeleter.deleteRecursively(skill)

        assertFalse(Files.exists(skill))
        assertTrue(Files.exists(sentinel), "deleting the skill must not traverse its nested junction")
    }
}
