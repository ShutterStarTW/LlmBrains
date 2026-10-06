package com.shutterstar.agenthub.environment.skills.sync.planning

import com.shutterstar.agenthub.writeSkillMd
import com.shutterstar.agenthub.environment.skills.discovery.SkillFingerprint
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus
import com.shutterstar.agenthub.environment.skills.sync.model.SyncOwner
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * [FakeTarget] models a [SkillSyncTarget] the way real adapters do: `globalSkillDirectory()` is a
 * *root* (e.g. `~/.claude/skills`), not a specific skill's directory — the observer appends the
 * canonical skill's directory name itself. Every fixture below writes/links content one level
 * below the [FakeTarget] root, at `<root>/canonical` (canonical is always created as
 * `root.resolve("canonical")`, so that's its directory name).
 */
class SkillTargetObserverTest {
    @TempDir
    lateinit var root: Path

    private val observer = SkillTargetObserver()
    private val fingerprint = SkillFingerprint()

    @Test
    fun `missing target directory is NOT_AVAILABLE`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val target = FakeTarget("claude", root.resolve("missing-root"))

        val observed = observe(target, canonical)

        assertEquals(SkillTargetStatus.NOT_AVAILABLE, observed.status)
        assertEquals(SyncOwner.UNKNOWN, observed.owner, "nothing exists at the path to attribute an owner to")
    }

    @Test
    fun `missing canonical content is MISSING_SOURCE`() {
        val canonical = root.resolve("missing-canonical")
        val target = FakeTarget("claude", root.resolve("target-root"))

        val observed = observer.observe(target, canonical, null, SkillScope.GLOBAL, null)

        assertEquals(SkillTargetStatus.MISSING_SOURCE, observed.status)
        assertEquals(SyncOwner.UNKNOWN, observed.owner)
    }

    @Test
    fun `previous managed path is exposed as a review-only rename candidate`() {
        val canonical = writeSkillMd(root.resolve("renamed-skill"), "content")
        val targetRoot = Files.createDirectories(root.resolve("target-root"))
        val oldPath = writeSkillMd(targetRoot.resolve("old-skill-name"), "content")
        val managed = ManagedTarget(
            "claude",
            oldPath.toString(),
            SkillSyncMode.COPY,
            EffectiveSyncMode.COPY,
            fingerprint.calculate(oldPath),
        )

        val observed = observer.observe(
            FakeTarget("claude", targetRoot),
            canonical,
            fingerprint.calculate(canonical),
            SkillScope.GLOBAL,
            null,
            managedTarget = managed,
        )

        assertEquals(SkillTargetStatus.NOT_AVAILABLE, observed.status)
        assertEquals(oldPath.toAbsolutePath().normalize(), observed.renameCandidatePath)
        assertFalse(observed.ownershipVerified, "a rename candidate must never be adopted automatically")
    }

    @Test
    fun `identical directory content is IDENTICAL_UNMANAGED`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "same content")
        val targetRoot = root.resolve("target-root")
        writeSkillMd(targetRoot.resolve("canonical"), "same content")
        val target = FakeTarget("claude", targetRoot)

        val observed = observe(target, canonical)

        assertEquals(SkillTargetStatus.IDENTICAL_UNMANAGED, observed.status)
        assertEquals(SyncOwner.MANUAL, observed.owner, "no recorded ManagedTarget - presumptively someone/something else's, even though the content happens to match")
    }

    @Test
    fun `identical directory content with a managed target recorded is COPIED, not IDENTICAL_UNMANAGED`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "same content")
        val targetRoot = root.resolve("target-root")
        val targetPath = writeSkillMd(targetRoot.resolve("canonical"), "same content")
        val target = FakeTarget("claude", targetRoot)
        val managed = ManagedTarget(
            "claude",
            targetPath.toString(),
            SkillSyncMode.COPY,
            EffectiveSyncMode.COPY,
            fingerprint.calculate(targetPath),
        )

        val observed = observer.observe(
            target = target,
            canonicalPath = canonical,
            canonicalFingerprint = fingerprint.calculate(canonical),
            scope = SkillScope.GLOBAL,
            project = null,
            managedTarget = managed,
        )

        assertEquals(SkillTargetStatus.COPIED, observed.status)
        assertTrue(observed.ownershipVerified)
        assertEquals(SyncOwner.AGENTHUB, observed.owner)
    }

    @Test
    fun `supporting file drift makes otherwise identical skills DIFFERENT`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "same content")
        val targetRoot = root.resolve("target-root")
        val targetPath = writeSkillMd(targetRoot.resolve("canonical"), "same content")
        Files.createDirectories(canonical.resolve("scripts"))
        Files.createDirectories(targetPath.resolve("scripts"))
        Files.writeString(canonical.resolve("scripts/run.sh"), "canonical")
        Files.writeString(targetPath.resolve("scripts/run.sh"), "changed")

        val observed = observe(FakeTarget("claude", targetRoot), canonical)

        assertEquals(SkillTargetStatus.DIFFERENT, observed.status)
    }

    @Test
    fun `changed managed copy is not trusted as AgentHub-owned`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "canonical content")
        val targetRoot = root.resolve("target-root")
        val targetPath = writeSkillMd(targetRoot.resolve("canonical"), "original content")
        val recordedFingerprint = fingerprint.calculate(targetPath)
        Files.writeString(targetPath.resolve("SKILL.md"), "manual edit")
        val managed = ManagedTarget(
            "claude",
            targetPath.toString(),
            SkillSyncMode.COPY,
            EffectiveSyncMode.COPY,
            recordedFingerprint,
        )

        val observed = observer.observe(
            FakeTarget("claude", targetRoot),
            canonical,
            fingerprint.calculate(canonical),
            SkillScope.GLOBAL,
            null,
            managedTarget = managed,
        )

        assertEquals(SkillTargetStatus.DIFFERENT, observed.status)
        assertFalse(observed.ownershipVerified)
        assertEquals(SyncOwner.MANUAL, observed.owner, "a stale/unverifiable managed record must not still read as AgentHub-owned")
    }

    @Test
    fun `target that disallows links exposes no link mode`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val target = FakeTarget("claude", root.resolve("missing-root"), supportsLinks = false)

        val observed = observe(target, canonical)

        assertNull(observed.availableLinkMode)
    }

    @Test
    fun `different directory content is DIFFERENT`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "canonical content")
        val targetRoot = root.resolve("target-root")
        writeSkillMd(targetRoot.resolve("canonical"), "different content")
        val target = FakeTarget("claude", targetRoot)

        val observed = observe(target, canonical)

        assertEquals(SkillTargetStatus.DIFFERENT, observed.status)
    }

    @Test
    fun `symlink resolving to the canonical path is LINKED`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val targetRoot = Files.createDirectories(root.resolve("target-root"))
        val targetPath = targetRoot.resolve("canonical")
        val linked = runCatching { Files.createSymbolicLink(targetPath, canonical) }.isSuccess
        assumeTrue(linked, "symlink creation requires elevated privilege on this machine")
        val target = FakeTarget("claude", targetRoot)

        val observed = observe(target, canonical)

        assertEquals(SkillTargetStatus.LINKED, observed.status)
        assertEquals(SyncOwner.MANUAL, observed.owner, "correctly linked by coincidence, but nobody told AgentHub about it (no ManagedTarget)")
    }

    @Test
    fun `a symlink verified against a matching ManagedTarget record is AGENTHUB-owned`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val targetRoot = Files.createDirectories(root.resolve("target-root"))
        val targetPath = targetRoot.resolve("canonical")
        val linked = runCatching { Files.createSymbolicLink(targetPath, canonical) }.isSuccess
        assumeTrue(linked, "symlink creation requires elevated privilege on this machine")
        val managed = ManagedTarget("claude", targetPath.toString(), SkillSyncMode.SYMLINK, EffectiveSyncMode.SYMLINK, null)

        val observed = observer.observe(
            FakeTarget("claude", targetRoot),
            canonical,
            fingerprint.calculate(canonical),
            SkillScope.GLOBAL,
            null,
            managedTarget = managed,
        )

        assertEquals(SkillTargetStatus.LINKED, observed.status)
        assertTrue(observed.ownershipVerified)
        assertEquals(SyncOwner.AGENTHUB, observed.owner)
    }

    @Test
    fun `symlink pointing elsewhere is DIFFERENT, never silently LINKED`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val elsewhere = writeSkillMd(root.resolve("elsewhere"), "other content")
        val targetRoot = Files.createDirectories(root.resolve("target-root"))
        val targetPath = targetRoot.resolve("canonical")
        val linked = runCatching { Files.createSymbolicLink(targetPath, elsewhere) }.isSuccess
        assumeTrue(linked, "symlink creation requires elevated privilege on this machine")
        val target = FakeTarget("claude", targetRoot)

        val observed = observe(target, canonical)

        assertEquals(SkillTargetStatus.DIFFERENT, observed.status)
    }

    @Test
    fun `dangling symlink is BROKEN_LINK`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val danglingSource = root.resolve("does-not-exist")
        val targetRoot = Files.createDirectories(root.resolve("target-root"))
        val targetPath = targetRoot.resolve("canonical")
        val linked = runCatching { Files.createSymbolicLink(targetPath, danglingSource) }.isSuccess
        assumeTrue(linked, "symlink creation requires elevated privilege on this machine")
        val target = FakeTarget("claude", targetRoot)

        val observed = observe(target, canonical)

        assertEquals(SkillTargetStatus.BROKEN_LINK, observed.status)
    }

    @Test
    fun `an agent that natively reads the shared source is NATIVE, even with no per-agent root configured`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        // "codex" has supportsSharedAgentSkills=true in AgentCapabilityRegistry. No global/project
        // root is given at all - proving NATIVE short-circuits before any per-agent path resolution.
        val target = FakeTarget("codex", global = null, project = null)

        val observed = observe(target, canonical)

        assertEquals(SkillTargetStatus.NATIVE, observed.status)
        assertEquals(canonical, observed.targetPath)
        assertEquals(SyncOwner.NATIVE, observed.owner)
        assertFalse(observed.ownershipVerified)
    }

    @Test
    fun `NATIVE falls back to MISSING_SOURCE when the canonical itself has no content`() {
        val canonical = root.resolve("missing-canonical")
        val target = FakeTarget("codex", global = root.resolve("target-root"))

        val observed = observer.observe(target, canonical, null, SkillScope.GLOBAL, null)

        assertEquals(SkillTargetStatus.MISSING_SOURCE, observed.status)
    }

    @Test
    fun `agent without skill support is UNSUPPORTED`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val target = FakeTarget("gemini", root.resolve("target-root"))

        val observed = observe(target, canonical)

        assertEquals(SkillTargetStatus.UNSUPPORTED, observed.status)
        assertNull(observed.targetPath)
        assertEquals(SyncOwner.UNKNOWN, observed.owner)
    }

    @Test
    fun `project scope with no resolvable project directory is UNSUPPORTED`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val target = FakeTarget("claude", global = root.resolve("global"), project = null)
        val project = DiscoveredProject(
            identity = ProjectIdentity(id = "id", canonicalPath = null, gitRoot = null, gitRemote = null),
            name = "project",
            path = null,
            gitRoot = null,
            gitRemote = null,
            currentBranch = null,
            agents = emptyList(),
            lastActivity = null,
        )

        val observed = observer.observe(
            target = target,
            canonicalPath = canonical,
            canonicalFingerprint = fingerprint.calculate(canonical),
            scope = SkillScope.PROJECT,
            project = project,
        )

        assertEquals(SkillTargetStatus.UNSUPPORTED, observed.status)
    }

    @Test
    fun `identical copy at an alternate compatibility root is IDENTICAL_UNMANAGED, not NOT_AVAILABLE`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "same content")
        val alternateRoot = root.resolve("alternate-root")
        writeSkillMd(alternateRoot.resolve("canonical"), "same content")
        // "kiro" (not a supportsSharedAgentSkills agent) so NATIVE doesn't short-circuit this
        // alternate-compatibility-root check before it runs.
        val target = FakeTarget("kiro", global = root.resolve("native-root"), alternateGlobal = listOf(alternateRoot))

        val observed = observe(target, canonical)

        assertEquals(SkillTargetStatus.IDENTICAL_UNMANAGED, observed.status)
        assertEquals(alternateRoot.resolve("canonical"), observed.targetPath)
        assertFalse(observed.ownershipVerified, "synchronization never wrote to an alternate root, so it can't own what it finds there")
    }

    @Test
    fun `symlink to canonical at an alternate compatibility root is LINKED`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "content")
        val alternateRoot = Files.createDirectories(root.resolve("alternate-root"))
        val alternatePath = alternateRoot.resolve("canonical")
        val linked = runCatching { Files.createSymbolicLink(alternatePath, canonical) }.isSuccess
        assumeTrue(linked, "symlink creation requires elevated privilege on this machine")
        val target = FakeTarget("kiro", global = root.resolve("native-root"), alternateGlobal = listOf(alternateRoot))

        val observed = observe(target, canonical)

        assertEquals(SkillTargetStatus.LINKED, observed.status)
        assertEquals(alternatePath, observed.targetPath)
    }

    @Test
    fun `diverged content at an alternate root does not mask NOT_AVAILABLE`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "canonical content")
        val alternateRoot = root.resolve("alternate-root")
        writeSkillMd(alternateRoot.resolve("canonical"), "different content")
        val target = FakeTarget("kiro", global = root.resolve("native-root"), alternateGlobal = listOf(alternateRoot))

        val observed = observe(target, canonical)

        assertEquals(SkillTargetStatus.NOT_AVAILABLE, observed.status)
    }

    @Test
    fun `alternate roots are only consulted when the native root has nothing`() {
        val canonical = writeSkillMd(root.resolve("canonical"), "same content")
        val nativeRoot = root.resolve("native-root")
        writeSkillMd(nativeRoot.resolve("canonical"), "different content")
        val alternateRoot = root.resolve("alternate-root")
        writeSkillMd(alternateRoot.resolve("canonical"), "same content")
        val target = FakeTarget("kiro", global = nativeRoot, alternateGlobal = listOf(alternateRoot))

        val observed = observe(target, canonical)

        assertEquals(SkillTargetStatus.DIFFERENT, observed.status)
        assertEquals(nativeRoot.resolve("canonical"), observed.targetPath)
    }

    private fun observe(target: SkillSyncTarget, canonical: Path) = observer.observe(
        target = target,
        canonicalPath = canonical,
        canonicalFingerprint = fingerprint.calculate(canonical),
        scope = SkillScope.GLOBAL,
        project = null,
    )

    private class FakeTarget(
        override val agentId: String,
        private val global: Path? = null,
        private val project: Path? = null,
        private val supportsLinks: Boolean = true,
        private val alternateGlobal: List<Path> = emptyList(),
        private val alternateProject: List<Path> = emptyList(),
    ) : SkillSyncTarget {
        override fun globalSkillDirectory(): Path? = global

        override fun projectSkillDirectory(project: DiscoveredProject): Path? = this.project

        override fun supportsLinkedSkills(): Boolean = supportsLinks

        override fun alternateGlobalSkillDirectories(): List<Path> = alternateGlobal

        override fun alternateProjectSkillDirectories(project: DiscoveredProject): List<Path> = alternateProject
    }
}
