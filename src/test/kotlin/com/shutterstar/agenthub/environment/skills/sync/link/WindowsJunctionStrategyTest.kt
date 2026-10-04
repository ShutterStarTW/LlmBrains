package com.shutterstar.agenthub.environment.skills.sync.link

import com.shutterstar.agenthub.OsDetector
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.discovery.SkillFingerprint
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.environment.skills.sync.planning.SkillTargetObserver
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WindowsJunctionStrategyTest {
    @TempDir
    lateinit var root: Path

    private val strategy = WindowsJunctionStrategy()

    @BeforeEach
    fun skipUnlessWindows() {
        assumeTrue(OsDetector.isWindows())
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
    fun `canLink rejects a cross-filesystem target so planning can preview copy fallback`() {
        val source = Files.createDirectory(root.resolve("source"))
        val target = root.resolve("target")
        val crossFilesystem = WindowsJunctionStrategy { path ->
            if (path.fileName?.toString() == "source") "source-volume" else "target-volume"
        }

        assertFalse(crossFilesystem.canLink(source, target))
    }

    @Test
    fun `canLink rejects a network target`() {
        val source = Files.createDirectory(root.resolve("source"))

        assertFalse(strategy.canLink(source, Path.of("\\\\server\\share\\target")))
    }

    @Test
    fun `cmd metacharacters force copy fallback and are never executed`() {
        val source = Files.createDirectory(root.resolve("source&ver"))
        val target = root.resolve("target")

        assertFalse(strategy.canLink(source, target))
        assertTrue(strategy.createLink(source, target) is LinkResult.Failure)
        assertFalse(Files.exists(target))
    }

    @Test
    fun `createLink creates a junction resolving to the source`() {
        val source = Files.createDirectory(root.resolve("source"))
        Files.writeString(source.resolve("marker.txt"), "hello")
        val target = root.resolve("target")

        val result = strategy.createLink(source, target)

        assertTrue(result is LinkResult.Success)
        assertEquals(EffectiveSyncMode.JUNCTION, (result as LinkResult.Success).effectiveMode)
        assertTrue(Files.isDirectory(target))
        assertTrue(Files.exists(target.resolve("marker.txt")))
    }

    @Test
    fun `createLink fails when the target's parent directory does not exist`() {
        val source = Files.createDirectory(root.resolve("source"))
        val target = root.resolve("missing-parent").resolve("target")

        val result = strategy.createLink(source, target)

        assertTrue(result is LinkResult.Failure)
    }

    @Test
    fun `observer detects a broken managed junction for repair`() {
        val canonical = Files.createDirectory(root.resolve("canonical"))
        Files.writeString(canonical.resolve("SKILL.md"), "canonical")
        val obsolete = Files.createDirectory(root.resolve("obsolete"))
        Files.writeString(obsolete.resolve("SKILL.md"), "obsolete")
        val targetRoot = Files.createDirectory(root.resolve("target-root"))
        val targetPath = targetRoot.resolve("canonical")
        assertTrue(strategy.createLink(obsolete, targetPath) is LinkResult.Success)
        Files.delete(obsolete.resolve("SKILL.md"))
        Files.delete(obsolete)
        val target = object : SkillSyncTarget {
            override val agentId: String = "claude"
            override fun globalSkillDirectory(): Path = targetRoot
            override fun projectSkillDirectory(project: DiscoveredProject): Path? = null
            override fun supportsLinkedSkills(): Boolean = true
        }

        val observed = SkillTargetObserver().observe(
            target,
            canonical,
            SkillFingerprint().calculate(canonical),
            SkillScope.GLOBAL,
            null,
            managedTarget = ManagedTarget(
                "claude",
                targetPath.toString(),
                SkillSyncMode.SYMLINK,
                EffectiveSyncMode.JUNCTION,
                null,
            ),
        )

        assertEquals(SkillTargetStatus.BROKEN_LINK, observed.status)
        assertTrue(observed.ownershipVerified)
        assertEquals(EffectiveSyncMode.JUNCTION, observed.managedMode)
        assertEquals(canonical, observed.managedLinkTarget)
    }
}
