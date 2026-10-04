package com.shutterstar.agenthub.environment.skills.sync.execution

import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.sync.model.EffectiveSyncMode
import com.shutterstar.agenthub.environment.skills.sync.model.SkillSyncMode
import com.shutterstar.agenthub.environment.skills.sync.ownership.ManagedTarget
import com.shutterstar.agenthub.environment.skills.sync.ownership.SkillInstanceKey
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

internal enum class PersistedStepKind { CREATE_LINK, COPY_SKILL, REMOVE_EXISTING }

/** The mutation shape needed by Undo. [requiresBackup] distinguishes replacement from creation. */
internal data class PersistedReversalStep(
    val agentId: String,
    val kind: PersistedStepKind,
    val path: String,
    val requiresBackup: Boolean = false,
)

internal data class PersistedVerification(val agentId: String, val path: String, val expectedFingerprint: String)

internal data class PersistedOperationJournal(
    val operationId: String,
    val skillId: String,
    val instanceKey: SkillInstanceKey,
    val canonicalPath: Path,
    val reversalSteps: List<PersistedReversalStep>,
    val verifications: List<PersistedVerification>,
    val previousManagedTargets: Map<String, ManagedTarget?>,
)

/**
 * Atomic Properties sidecar per operation. Repeated values use indexed keys rather than delimiter
 * encoding so every valid platform path round-trips unchanged.
 */
internal object OperationJournalStore {
    fun write(journal: PersistedOperationJournal, backupRoot: Path) {
        val properties = Properties()
        properties.setProperty(KEY_SKILL_ID, journal.skillId)
        properties.setProperty(KEY_RUNTIME_ID, journal.instanceKey.runtimeId)
        properties.setProperty(KEY_SCOPE, journal.instanceKey.scope.name)
        properties.setProperty(KEY_CONTEXT_PATH, journal.instanceKey.contextPath)
        properties.setProperty(KEY_CANONICAL_PATH, journal.canonicalPath.toString())

        properties.setProperty(KEY_STEP_COUNT, journal.reversalSteps.size.toString())
        journal.reversalSteps.forEachIndexed { index, step ->
            properties.setProperty("step.$index.kind", step.kind.name)
            properties.setProperty("step.$index.agentId", step.agentId)
            properties.setProperty("step.$index.path", step.path)
            properties.setProperty("step.$index.requiresBackup", step.requiresBackup.toString())
        }

        properties.setProperty(KEY_VERIFICATION_COUNT, journal.verifications.size.toString())
        journal.verifications.forEachIndexed { index, verification ->
            properties.setProperty("verification.$index.agentId", verification.agentId)
            properties.setProperty("verification.$index.path", verification.path)
            properties.setProperty("verification.$index.fingerprint", verification.expectedFingerprint)
        }

        val managedEntries = journal.previousManagedTargets.entries.sortedBy { it.key }
        properties.setProperty(KEY_MANAGED_COUNT, managedEntries.size.toString())
        managedEntries.forEachIndexed { index, (agentId, managed) ->
            properties.setProperty("managed.$index.agentId", agentId)
            properties.setProperty("managed.$index.present", (managed != null).toString())
            if (managed != null) {
                properties.setProperty("managed.$index.path", managed.path)
                properties.setProperty("managed.$index.requestedMode", managed.requestedMode.name)
                properties.setProperty("managed.$index.effectiveMode", managed.effectiveMode.name)
                managed.lastFingerprint?.let { properties.setProperty("managed.$index.lastFingerprint", it) }
                managed.operationId?.let { properties.setProperty("managed.$index.operationId", it) }
            }
        }

        val path = journalPathFor(backupRoot, journal.operationId)
        val temporary = path.resolveSibling(".${path.fileName}.tmp-${UUID.randomUUID()}")
        Files.createDirectories(path.parent)
        try {
            Files.newBufferedWriter(temporary).use { writer -> properties.store(writer, null) }
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun read(backupRoot: Path, operationId: String): PersistedOperationJournal? {
        val path = journalPathFor(backupRoot, operationId)
        val properties = Properties()
        runCatching {
            Files.newBufferedReader(path).use { reader -> properties.load(reader) }
        }.onFailure { return null }

        val skillId = properties.getProperty(KEY_SKILL_ID) ?: return null
        val runtimeId = properties.getProperty(KEY_RUNTIME_ID) ?: return null
        val scope = properties.getProperty(KEY_SCOPE)?.let { runCatching { SkillScope.valueOf(it) }.getOrNull() } ?: return null
        val contextPath = properties.getProperty(KEY_CONTEXT_PATH) ?: return null
        val canonicalPath = properties.getProperty(KEY_CANONICAL_PATH)?.let { runCatching { Path.of(it) }.getOrNull() } ?: return null

        val stepCount = readCount(properties, KEY_STEP_COUNT) ?: return null
        val verificationCount = readCount(properties, KEY_VERIFICATION_COUNT) ?: return null
        val managedCount = readCount(properties, KEY_MANAGED_COUNT) ?: return null
        val steps = (0 until stepCount).map { index ->
            PersistedReversalStep(
                agentId = properties.getProperty("step.$index.agentId") ?: return null,
                kind = properties.getProperty("step.$index.kind")
                    ?.let { runCatching { PersistedStepKind.valueOf(it) }.getOrNull() }
                    ?: return null,
                path = properties.getProperty("step.$index.path") ?: return null,
                requiresBackup = properties.getProperty("step.$index.requiresBackup")?.toBooleanStrictOrNull()
                    ?: return null,
            )
        }
        val verifications = (0 until verificationCount).map { index ->
            PersistedVerification(
                agentId = properties.getProperty("verification.$index.agentId") ?: return null,
                path = properties.getProperty("verification.$index.path") ?: return null,
                expectedFingerprint = properties.getProperty("verification.$index.fingerprint") ?: return null,
            )
        }
        val previousManagedTargets = (0 until managedCount).associate { index ->
            val agentId = properties.getProperty("managed.$index.agentId") ?: return null
            val present = properties.getProperty("managed.$index.present")?.toBooleanStrictOrNull() ?: return null
            agentId to if (present) decodeManagedTarget(properties, index, agentId) ?: return null else null
        }

        return PersistedOperationJournal(
            operationId = operationId,
            skillId = skillId,
            instanceKey = SkillInstanceKey(runtimeId, scope, contextPath, skillId),
            canonicalPath = canonicalPath,
            reversalSteps = steps,
            verifications = verifications,
            previousManagedTargets = previousManagedTargets,
        )
    }

    private fun readCount(properties: Properties, key: String): Int? =
        properties.getProperty(key)?.toIntOrNull()?.takeIf { it in 0..MAX_RECORDS }

    private fun decodeManagedTarget(properties: Properties, index: Int, agentId: String): ManagedTarget? {
        val requestedMode = properties.getProperty("managed.$index.requestedMode")
            ?.let { runCatching { SkillSyncMode.valueOf(it) }.getOrNull() }
            ?: return null
        val effectiveMode = properties.getProperty("managed.$index.effectiveMode")
            ?.let { runCatching { EffectiveSyncMode.valueOf(it) }.getOrNull() }
            ?: return null
        return ManagedTarget(
            agentId = agentId,
            path = properties.getProperty("managed.$index.path") ?: return null,
            requestedMode = requestedMode,
            effectiveMode = effectiveMode,
            lastFingerprint = properties.getProperty("managed.$index.lastFingerprint"),
            operationId = properties.getProperty("managed.$index.operationId"),
        )
    }

    private fun journalPathFor(backupRoot: Path, operationId: String): Path =
        backupRoot.resolve(operationId).resolve("journal.properties")

    private const val KEY_SKILL_ID = "skillId"
    private const val KEY_RUNTIME_ID = "runtimeId"
    private const val KEY_SCOPE = "scope"
    private const val KEY_CONTEXT_PATH = "contextPath"
    private const val KEY_CANONICAL_PATH = "canonicalPath"
    private const val KEY_STEP_COUNT = "step.count"
    private const val KEY_VERIFICATION_COUNT = "verification.count"
    private const val KEY_MANAGED_COUNT = "managed.count"
    private const val MAX_RECORDS = 10_000
}
