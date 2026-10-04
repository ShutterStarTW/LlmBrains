package com.shutterstar.agenthub.environment.skills.sync.link

import com.shutterstar.agenthub.OsDetector
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class CopyStrategyTest {
    @TempDir
    lateinit var root: Path

    private val strategy = CopyStrategy()

    @Test
    fun `canLink is true whenever source is a directory`() {
        val source = Files.createDirectory(root.resolve("source"))

        assertTrue(strategy.canLink(source, root.resolve("does-not-matter")))
    }

    @Test
    fun `createLink reproduces nested files and content at the target`() {
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("SKILL.md"), "# Review")
        val scriptsDir = Files.createDirectory(source.resolve("scripts"))
        Files.writeString(scriptsDir.resolve("foo.sh"), "echo hi")
        val target = root.resolve("target")

        val result = strategy.createLink(source, target)

        assertEquals(LinkResult.Success(EffectiveSyncMode.COPY), result)
        assertEquals("# Review", Files.readString(target.resolve("SKILL.md")))
        assertEquals("echo hi", Files.readString(target.resolve("scripts").resolve("foo.sh")))
    }

    @Test
    fun `createLink preserves the executable bit on Unix`() {
        assumeFalse(OsDetector.isWindows())

        val source = Files.createDirectory(root.resolve("source"))
        val script = Files.writeString(source.resolve("run.sh"), "echo hi")
        Files.setPosixFilePermissions(
            script,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        val target = root.resolve("target")

        strategy.createLink(source, target)

        val copiedPermissions = Files.getPosixFilePermissions(target.resolve("run.sh"))
        assertTrue(PosixFilePermission.OWNER_EXECUTE in copiedPermissions)
    }

    @Test
    fun `createLink stages the copy next to the target and only renames it into place at the end`() {
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("SKILL.md"), "# Review")
        val target = root.resolve("target")

        strategy.createLink(source, target)

        val siblings = Files.list(root).use { it.toList() }
        assertEquals(listOf(source, target).sorted(), siblings.sorted(), "no leftover staging directory once the move succeeds")
    }

    @Test
    fun `a failed final move cleans up the staging directory and leaves a pre-existing target untouched`() {
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("SKILL.md"), "content")
        val target = root.resolve("target")
        // A plain file already sitting at the target path blocks Files.move(ATOMIC_MOVE) — occupied
        // targets must be swapped via AtomicPathReplace; CopyStrategy must never paper over that by
        // deleting what it didn't create.
        Files.writeString(target, "not a directory - blocks the rename")

        val result = strategy.createLink(source, target)

        assertTrue(result is LinkResult.Failure)
        assertEquals("not a directory - blocks the rename", Files.readString(target))
        val leftoverStaging = Files.list(root).use { it.toList() }.filter { it.fileName.toString().contains(".agenthub-tmp-") }
        assertTrue(leftoverStaging.isEmpty(), "the staging directory must be cleaned up after a failed move")
    }

    @Test
    fun `createLink does not follow a symlink into an external directory`() {
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.writeString(outside.resolve("secret.txt"), "do not copy me")
        val source = Files.createDirectory(root.resolve("source"))

        val linkCreated = runCatching { Files.createSymbolicLink(source.resolve("escape"), outside) }.isSuccess
        assumeTrue(linkCreated, "symlink creation requires elevated privilege on this machine")

        val target = root.resolve("target")
        val result = strategy.createLink(source, target)

        assertEquals(LinkResult.Success(EffectiveSyncMode.COPY), result)
        assertTrue(Files.exists(target.resolve("escape")))
        assertTrue(Files.isSymbolicLink(target.resolve("escape")))
    }

    @Test
    fun `createLink refuses to traverse a nested Windows junction`() {
        assumeTrue(OsDetector.isWindows())
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.writeString(outside.resolve("secret.txt"), "do not copy me")
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("SKILL.md"), "content")
        assumeTrue(
            WindowsJunctionStrategy().createLink(outside, source.resolve("escape")) is LinkResult.Success,
            "junction creation is unavailable",
        )

        val target = root.resolve("target")
        val result = strategy.createLink(source, target)

        assertTrue(result is LinkResult.Failure)
        assertTrue(Files.notExists(target))
        assertTrue(
            Files.list(root).use { paths -> paths.noneMatch { it.fileName.toString().contains(".agenthub-tmp-") } },
            "failed copies must clean their staging directory",
        )
    }
}
