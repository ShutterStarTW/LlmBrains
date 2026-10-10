package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.writeSkillMd
import com.shutterstar.agenthub.environment.skills.sync.link.CopyStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.LinkResult
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class AtomicPathReplaceTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `replace swaps in staged content and removes the displaced original`() {
        val target = writeSkillMd(root.resolve("skill"), "old")
        val source = writeSkillMd(root.resolve("source"), "new")

        val result = AtomicPathReplace.replace(target) { staging ->
            CopyStrategy().createLink(source, staging)
        }

        assertTrue(result is LinkResult.Success)
        assertEquals("new", Files.readString(target.resolve("SKILL.md")))
        val leftovers = Files.list(root).use { paths ->
            paths.filter {
                val name = it.fileName.toString()
                name.contains("agenthub-install-") || name.contains("agenthub-displaced-")
            }.toList()
        }
        assertTrue(leftovers.isEmpty(), "no staging or displaced siblings should remain after success")
    }

    @Test
    fun `failed install leaves the live target untouched`() {
        val target = writeSkillMd(root.resolve("skill"), "keep-me")

        val result = AtomicPathReplace.replace(target) {
            LinkResult.Failure("disk full")
        }

        assertTrue(result is LinkResult.Failure)
        assertEquals("keep-me", Files.readString(target.resolve("SKILL.md")))
        assertTrue(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `install runs while the original still occupies the live path`() {
        val target = writeSkillMd(root.resolve("skill"), "original")
        val source = writeSkillMd(root.resolve("source"), "replacement")
        var originalPresentDuringInstall = false

        val result = AtomicPathReplace.replace(target) { staging ->
            originalPresentDuringInstall = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            CopyStrategy().createLink(source, staging)
        }

        assertTrue(result is LinkResult.Success)
        assertTrue(originalPresentDuringInstall, "live path must not be deleted before the replacement is fully staged")
        assertEquals("replacement", Files.readString(target.resolve("SKILL.md")))
    }

    @Test
    fun `replace into a missing path installs without requiring a prior delete`() {
        val target = root.resolve("skill")
        val source = writeSkillMd(root.resolve("source"), "fresh")

        val result = AtomicPathReplace.replace(target) { staging ->
            CopyStrategy().createLink(source, staging)
        }

        assertEquals(LinkResult.Success(EffectiveSyncMode.COPY), result)
        assertEquals("fresh", Files.readString(target.resolve("SKILL.md")))
    }

    @Test
    fun `should restore the original when the staged swap fails`() {
        val target = writeSkillMd(root.resolve("skill"), "unique-original")
        val source = writeSkillMd(root.resolve("source"), "replacement")
        var moves = 0
        val result = AtomicPathReplace.replace(target, move = { from, to ->
            moves++
            if (moves == 2) throw java.io.IOException("Injected swap failure")
            Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            Unit
        }) { staging -> CopyStrategy().createLink(source, staging) }

        assertTrue(result is LinkResult.Failure)
        assertEquals(3, moves)
        assertEquals("unique-original", Files.readString(target.resolve("SKILL.md")))
        assertEquals(setOf("skill", "source"), Files.list(root).use { paths ->
            paths.map { it.fileName.toString() }.toList().toSet()
        })
    }
}
