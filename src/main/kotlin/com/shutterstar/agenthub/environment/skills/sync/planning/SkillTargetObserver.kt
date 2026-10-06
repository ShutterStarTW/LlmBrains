package com.shutterstar.agenthub.environment.skills.sync.planning

import com.shutterstar.agenthub.environment.capabilities.AgentCapabilityRegistry
import com.shutterstar.agenthub.environment.skills.discovery.SkillFingerprint
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.link.FileLinkStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.UnixSymlinkStrategy
import com.shutterstar.agenthub.environment.skills.sync.link.WindowsJunctionStrategy
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncTarget
import com.shutterstar.agenthub.environment.skills.sync.model.SkillTargetStatus
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Read-only, filesystem-touching. Never mutates anything and never decides what to do about what
 * it observes — that's [SkillSyncPlanner]'s job.
 */
internal class SkillTargetObserver(
    private val fingerprint: SkillFingerprint = SkillFingerprint(),
    private val linkStrategies: List<Pair<FileLinkStrategy, EffectiveSyncMode>> = listOf(
        UnixSymlinkStrategy() to EffectiveSyncMode.SYMLINK,
        WindowsJunctionStrategy() to EffectiveSyncMode.JUNCTION,
    ),
) {
    fun observe(
        target: SkillSyncTarget,
        canonicalPath: Path,
        canonicalFingerprint: String?,
        scope: SkillScope,
        project: DiscoveredProject?,
        requestedMode: SkillSyncMode = SkillSyncMode.SYMLINK,
        managedTarget: ManagedTarget? = null,
        nativeShortCircuit: Boolean = true,
        /** The exact directory to observe instead of `<agent skills root>/<name>` (a vendor copy lives in a sub-folder). */
        exactTargetPath: Path? = null,
    ): ObservedSkillTarget {
        val capabilities = AgentCapabilityRegistry.capabilitiesFor(target.agentId)
        if (!capabilities.supportsSkills) {
            return ObservedSkillTarget(target.agentId, null, SkillTargetStatus.UNSUPPORTED, requestedMode)
        }
        // This agent's own CLI reads the shared .agents/skills source directly - creating a link or
        // copy in its per-agent directory would just be a redundant duplicate. Only short-circuits
        // when the canonical actually has content; if it doesn't, fall through to the normal path so
        // MISSING_SOURCE (below) still reports correctly instead of a false NATIVE.
        if (nativeShortCircuit && canonicalFingerprint != null && capabilities.supportsSharedAgentSkills) {
            return ObservedSkillTarget(target.agentId, canonicalPath, SkillTargetStatus.NATIVE, requestedMode)
        }

        val targetPath = exactTargetPath ?: resolveTargetPath(target, canonicalPath, scope, project)
            ?: return ObservedSkillTarget(target.agentId, null, SkillTargetStatus.UNSUPPORTED, requestedMode)
        val verifiedRecord = managedTarget?.takeIf { managedPathMatches(it, targetPath) }
        val renameCandidate = renameCandidate(managedTarget, targetPath)

        if (canonicalFingerprint == null) {
            return ObservedSkillTarget(
                target.agentId,
                targetPath,
                SkillTargetStatus.MISSING_SOURCE,
                requestedMode,
                ownershipVerified = verifiedRecord != null,
                managedMode = verifiedRecord?.effectiveMode,
                renameCandidatePath = renameCandidate,
            )
        }

        val availableLinkMode = if (target.supportsLinkedSkills()) {
            linkStrategies.firstNotNullOfOrNull { (strategy, mode) ->
                mode.takeIf { strategy.canLink(canonicalPath, targetPath) }
            }
        } else {
            null
        }

        if (!Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            observeAlternates(target, canonicalPath, canonicalFingerprint, scope, project, requestedMode, availableLinkMode)?.let { return it }
            return ObservedSkillTarget(
                target.agentId,
                targetPath,
                SkillTargetStatus.NOT_AVAILABLE,
                requestedMode,
                availableLinkMode,
                ownershipVerified = verifiedRecord != null,
                managedMode = verifiedRecord?.effectiveMode,
                renameCandidatePath = renameCandidate,
            )
        }

        if (Files.isSymbolicLink(targetPath)) {
            return observeLink(target.agentId, targetPath, canonicalPath, requestedMode, availableLinkMode, verifiedRecord)
        }

        if (runCatching { Files.isSameFile(targetPath, canonicalPath) }.getOrDefault(false)) {
            val ownershipVerified = verifiedRecord?.effectiveMode == EffectiveSyncMode.JUNCTION
            return ObservedSkillTarget(
                target.agentId,
                targetPath,
                SkillTargetStatus.LINKED,
                requestedMode,
                availableLinkMode,
                ownershipVerified = ownershipVerified,
                managedMode = verifiedRecord?.effectiveMode,
                managedLinkTarget = canonicalPath,
            )
        }

        val verifiedMode = verifiedRecord?.effectiveMode
        if (verifiedMode in LINK_MODES) {
            val targetDirectoryIsReachable = Files.isDirectory(targetPath)
            val isDanglingManagedJunction =
                verifiedMode == EffectiveSyncMode.JUNCTION &&
                    !targetDirectoryIsReachable &&
                    Files.isDirectory(targetPath, LinkOption.NOFOLLOW_LINKS)
            return ObservedSkillTarget(
                target.agentId,
                targetPath,
                if (targetDirectoryIsReachable) SkillTargetStatus.DIFFERENT else SkillTargetStatus.BROKEN_LINK,
                requestedMode,
                availableLinkMode,
                ownershipVerified = isDanglingManagedJunction,
                managedMode = verifiedMode,
                managedLinkTarget = canonicalPath.takeIf { isDanglingManagedJunction },
            )
        }

        return observeDirectory(
            target.agentId,
            targetPath,
            canonicalFingerprint,
            requestedMode,
            availableLinkMode,
            verifiedRecord,
        )
    }

    /**
     * [SkillSyncTarget.globalSkillDirectory]/[SkillSyncTarget.projectSkillDirectory] return the
     * agent's skills *root* (e.g. `~/.claude/skills`), not a specific skill's directory — the
     * canonical skill's directory name (`canonicalPath.fileName`, e.g. `php-review`) must be
     * appended to get the actual comparable per-skill target path (e.g. `~/.claude/skills/php-review`).
     */
    private fun resolveTargetPath(
        target: SkillSyncTarget,
        canonicalPath: Path,
        scope: SkillScope,
        project: DiscoveredProject?,
    ): Path? {
        val root = when (scope) {
            SkillScope.GLOBAL -> target.globalSkillDirectory()
            SkillScope.PROJECT -> project?.let(target::projectSkillDirectory)
        } ?: return null
        return root.resolve(canonicalPath.fileName)
    }

    /**
     * The skill is absent at [target]'s native root — before reporting [SkillTargetStatus.NOT_AVAILABLE],
     * check the agent's other discovery-recognized roots ([SkillSyncTarget.alternateGlobalSkillDirectories]/
     * [SkillSyncTarget.alternateProjectSkillDirectories]) for a link or byte-identical copy already
     * there, so a skill the discovery layer already lists for this agent isn't shown as unshared in
     * the "Share with…" checklist. Never [ObservedSkillTarget.ownershipVerified] — synchronization
     * never wrote to an alternate root, so it can never manage or remove what it finds there. A
     * present-but-diverged alternate copy is skipped (not reported DIFFERENT): only an exact match
     * counts as "already shared" here, and other alternates or the native NOT_AVAILABLE case still
     * get their turn.
     */
    private fun observeAlternates(
        target: SkillSyncTarget,
        canonicalPath: Path,
        canonicalFingerprint: String,
        scope: SkillScope,
        project: DiscoveredProject?,
        requestedMode: SkillSyncMode,
        availableLinkMode: EffectiveSyncMode?,
    ): ObservedSkillTarget? {
        val roots = when (scope) {
            SkillScope.GLOBAL -> target.alternateGlobalSkillDirectories()
            SkillScope.PROJECT -> project?.let(target::alternateProjectSkillDirectories) ?: emptyList()
        }
        for (root in roots) {
            val altPath = root.resolve(canonicalPath.fileName)
            if (!Files.exists(altPath, LinkOption.NOFOLLOW_LINKS)) continue

            if (Files.isSymbolicLink(altPath)) {
                val resolvedAlt = runCatching { altPath.toRealPath() }.getOrNull() ?: continue
                val resolvedCanonical = runCatching { canonicalPath.toRealPath() }.getOrDefault(canonicalPath)
                if (resolvedAlt == resolvedCanonical) {
                    return ObservedSkillTarget(target.agentId, altPath, SkillTargetStatus.LINKED, requestedMode, availableLinkMode)
                }
                continue
            }

            val altFingerprint = fingerprint.calculate(altPath) ?: continue
            if (altFingerprint == canonicalFingerprint) {
                return ObservedSkillTarget(
                    target.agentId,
                    altPath,
                    SkillTargetStatus.IDENTICAL_UNMANAGED,
                    requestedMode,
                    availableLinkMode,
                    altFingerprint,
                )
            }
        }
        return null
    }

    private fun observeLink(
        agentId: String,
        targetPath: Path,
        canonicalPath: Path,
        requestedMode: SkillSyncMode,
        availableLinkMode: EffectiveSyncMode?,
        managedTarget: ManagedTarget?,
    ): ObservedSkillTarget {
        val resolvedTarget = runCatching { targetPath.toRealPath() }.getOrNull()
            ?: return ObservedSkillTarget(
                agentId,
                targetPath,
                SkillTargetStatus.BROKEN_LINK,
                requestedMode,
                availableLinkMode,
                ownershipVerified = managedTarget?.effectiveMode == EffectiveSyncMode.SYMLINK,
                managedMode = managedTarget?.effectiveMode,
                managedLinkTarget = managedTarget?.takeIf { it.effectiveMode == EffectiveSyncMode.SYMLINK }?.let {
                    runCatching { Files.readSymbolicLink(targetPath) }.getOrNull()
                },
            )
        val resolvedCanonical = runCatching { canonicalPath.toRealPath() }.getOrDefault(canonicalPath)

        val status = if (resolvedTarget == resolvedCanonical) SkillTargetStatus.LINKED else SkillTargetStatus.DIFFERENT
        val ownershipVerified = status == SkillTargetStatus.LINKED && managedTarget?.effectiveMode == EffectiveSyncMode.SYMLINK
        return ObservedSkillTarget(
            agentId,
            targetPath,
            status,
            requestedMode,
            availableLinkMode,
            ownershipVerified = ownershipVerified,
            managedMode = managedTarget?.effectiveMode,
            managedLinkTarget = managedTarget?.takeIf { ownershipVerified }?.let { canonicalPath },
        )
    }

    private fun observeDirectory(
        agentId: String,
        targetPath: Path,
        canonicalFingerprint: String?,
        requestedMode: SkillSyncMode,
        availableLinkMode: EffectiveSyncMode?,
        managedTarget: ManagedTarget?,
    ): ObservedSkillTarget {
        val targetFingerprint = fingerprint.calculate(targetPath)
            ?: return ObservedSkillTarget(agentId, targetPath, SkillTargetStatus.ERROR, requestedMode, availableLinkMode)

        val verifiedManagedCopy = managedTarget?.takeIf {
            it.effectiveMode == EffectiveSyncMode.COPY &&
                it.lastFingerprint != null &&
                it.lastFingerprint == targetFingerprint
        }
        val status = when {
            verifiedManagedCopy != null -> SkillTargetStatus.COPIED
            targetFingerprint != canonicalFingerprint -> SkillTargetStatus.DIFFERENT
            else -> SkillTargetStatus.IDENTICAL_UNMANAGED
        }
        return ObservedSkillTarget(
            agentId,
            targetPath,
            status,
            requestedMode,
            availableLinkMode,
            targetFingerprint,
            ownershipVerified = verifiedManagedCopy != null,
            managedMode = verifiedManagedCopy?.effectiveMode,
        )
    }

    private fun managedPathMatches(managedTarget: ManagedTarget, targetPath: Path): Boolean {
        val recordedPath = try {
            Path.of(managedTarget.path)
        } catch (_: InvalidPathException) {
            return false
        }
        return recordedPath.toAbsolutePath().normalize() == targetPath.toAbsolutePath().normalize()
    }

    private fun renameCandidate(managedTarget: ManagedTarget?, targetPath: Path): Path? {
        val recordedPath = try {
            managedTarget?.path?.let(Path::of)
        } catch (_: InvalidPathException) {
            null
        } ?: return null
        val normalizedRecorded = recordedPath.toAbsolutePath().normalize()
        if (normalizedRecorded == targetPath.toAbsolutePath().normalize()) return null
        return normalizedRecorded.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
    }

    private companion object {
        val LINK_MODES = setOf(EffectiveSyncMode.SYMLINK, EffectiveSyncMode.JUNCTION)
    }
}
