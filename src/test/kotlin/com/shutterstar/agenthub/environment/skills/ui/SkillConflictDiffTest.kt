package com.shutterstar.agenthub.environment.skills.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SkillConflictDiffTest {
    @TempDir lateinit var root: Path

    @Test fun `compareDirectories reports modified, added and missing files, skipping identical ones`() {
        val canonical = Files.createDirectories(root.resolve("canonical"))
        val target = Files.createDirectories(root.resolve("target"))
        Files.writeString(canonical.resolve("SKILL.md"), "canonical content")
        Files.writeString(target.resolve("SKILL.md"), "target content")
        Files.writeString(canonical.resolve("shared.txt"), "same")
        Files.writeString(target.resolve("shared.txt"), "same")
        Files.writeString(canonical.resolve("only-canonical.txt"), "gone in target")
        Files.createDirectories(target.resolve("scripts"))
        Files.writeString(target.resolve("scripts/only-target.sh"), "new in target")

        val entries = SkillConflictDiff.compareDirectories(canonical, target)

        assertEquals(
            setOf(
                ConflictFileEntry("SKILL.md", ConflictFileStatus.MODIFIED),
                ConflictFileEntry("only-canonical.txt", ConflictFileStatus.MISSING),
                ConflictFileEntry("scripts/only-target.sh", ConflictFileStatus.ADDED),
            ),
            entries.toSet(),
        )
    }

    @Test fun `compareDirectories returns nothing for two identical directories`() {
        val canonical = Files.createDirectories(root.resolve("canonical"))
        val target = Files.createDirectories(root.resolve("target"))
        Files.writeString(canonical.resolve("SKILL.md"), "same content")
        Files.writeString(target.resolve("SKILL.md"), "same content")

        assertTrue(SkillConflictDiff.compareDirectories(canonical, target).isEmpty())
    }

    @Test fun `compareDirectories fails closed when a directory disappears`() {
        val canonical = Files.createDirectories(root.resolve("canonical"))
        Files.writeString(canonical.resolve("SKILL.md"), "content")
        val missingTarget = root.resolve("does-not-exist")

        assertThrows(java.io.IOException::class.java) {
            SkillConflictDiff.compareDirectories(canonical, missingTarget)
        }
    }

    @Test fun `compareDirectories fails closed when combined differences exceed limit`() {
        val canonical = Files.createDirectories(root.resolve("canonical"))
        val target = Files.createDirectories(root.resolve("target"))
        repeat(300) { index ->
            Files.writeString(canonical.resolve("canonical-$index.txt"), "left")
            Files.writeString(target.resolve("target-$index.txt"), "right")
        }

        assertThrows(java.io.IOException::class.java) {
            SkillConflictDiff.compareDirectories(canonical, target)
        }
    }

    @Test fun `compareDirectories fails closed when deeper files are omitted`() {
        val canonical = Files.createDirectories(root.resolve("canonical"))
        val target = Files.createDirectories(root.resolve("target"))
        var nested = canonical
        repeat(12) { nested = Files.createDirectory(nested.resolve("level$it")) }
        Files.writeString(nested.resolve("hidden.txt"), "left")

        assertThrows(java.io.IOException::class.java) {
            SkillConflictDiff.compareDirectories(canonical, target)
        }
    }

    @Test fun `readText returns regular content and rejects unavailable files`() {
        val dir = Files.createDirectories(root.resolve("dir"))
        Files.writeString(dir.resolve("SKILL.md"), "hello")

        assertEquals("hello", SkillConflictDiff.readText(dir, "SKILL.md"))
        assertThrows(java.io.IOException::class.java) {
            SkillConflictDiff.readText(dir, "missing.md")
        }
    }

    @Test fun `readText rejects symlinks and oversized files`() {
        val dir = Files.createDirectories(root.resolve("dir"))
        val outside = Files.writeString(root.resolve("outside.txt"), "private")
        val linked = runCatching { Files.createSymbolicLink(dir.resolve("linked.txt"), outside) }.isSuccess
        assumeTrue(linked, "symlink creation is unavailable")
        Files.write(dir.resolve("large.txt"), ByteArray(2 * 1024 * 1024 + 1))

        assertThrows(java.io.IOException::class.java) {
            SkillConflictDiff.readText(dir, "linked.txt")
        }
        assertThrows(java.io.IOException::class.java) {
            SkillConflictDiff.readText(dir, "large.txt")
        }
    }
}
