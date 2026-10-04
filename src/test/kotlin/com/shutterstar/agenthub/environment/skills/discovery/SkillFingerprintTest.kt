package com.shutterstar.agenthub.environment.skills.discovery

import com.shutterstar.agenthub.OsDetector
import com.shutterstar.agenthub.environment.skills.sync.link.LinkResult
import com.shutterstar.agenthub.environment.skills.sync.link.WindowsJunctionStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SkillFingerprintTest {
    @TempDir
    lateinit var root: Path

    private val fingerprint = SkillFingerprint()

    @Test
    fun `should include supporting files in the fingerprint`() {
        val first = writeSkill(root.resolve("first"), "same metadata", "first script")
        val second = writeSkill(root.resolve("second"), "same metadata", "second script")

        assertNotEquals(fingerprint.calculate(first), fingerprint.calculate(second))
    }

    @Test
    fun `should ignore filesystem timestamps`() {
        val first = writeSkill(root.resolve("first"), "same metadata", "same script")
        val second = writeSkill(root.resolve("second"), "same metadata", "same script")
        Files.setLastModifiedTime(second.resolve("scripts/run.sh"), java.nio.file.attribute.FileTime.fromMillis(1))

        assertEquals(fingerprint.calculate(first), fingerprint.calculate(second))
    }

    @Test
    fun `should ignore local metadata directories`() {
        val first = writeSkill(root.resolve("first"), "same metadata", "same script")
        val second = writeSkill(root.resolve("second"), "same metadata", "same script")
        Files.createDirectories(second.resolve(".git"))
        Files.writeString(second.resolve(".git/index"), "local state")
        Files.writeString(second.resolve(".DS_Store"), "local state")

        assertEquals(fingerprint.calculate(first), fingerprint.calculate(second))
    }

    @Test
    fun `should reject a directory without SKILL md`() {
        val directory = Files.createDirectories(root.resolve("not-a-skill"))
        Files.writeString(directory.resolve("README.md"), "content")

        assertNull(fingerprint.calculate(directory))
    }

    @Test
    fun `should reject a skill containing a nested Windows junction`() {
        assumeTrue(OsDetector.isWindows())
        val skill = writeSkill(root.resolve("skill"), "metadata", "script")
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.writeString(outside.resolve("private.txt"), "private")
        assumeTrue(
            WindowsJunctionStrategy().createLink(outside, skill.resolve("references")) is LinkResult.Success,
            "junction creation is unavailable",
        )

        assertNull(fingerprint.calculate(skill))
    }

    private fun writeSkill(directory: Path, metadata: String, script: String): Path {
        Files.createDirectories(directory.resolve("scripts"))
        Files.writeString(directory.resolve("SKILL.md"), metadata)
        Files.writeString(directory.resolve("scripts/run.sh"), script)
        return directory
    }
}
