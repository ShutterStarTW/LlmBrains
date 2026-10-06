package com.shutterstar.agenthub.environment.skills.ui

import com.shutterstar.agenthub.environment.capabilities.AgentCapabilityRegistry
import com.shutterstar.agenthub.environment.model.EnvironmentWarning
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.model.SkillConsistency
import com.shutterstar.agenthub.environment.skills.model.SkillRootOwner
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.environment.skills.model.SkillSource
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path
import java.util.Locale
import com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget

internal data class SkillBrowserContext(
    val scope: SkillScope,
    /**
     * Null while [scope] is [SkillScope.PROJECT] means "all projects with skills" - the project
     * filter's default - rather than an invalid selection; [SkillOccurrenceRow.context] for a row
     * discovered under that aggregate request is always a concrete per-project context, never this
     * one, so mutation code that reads `row.context.project` never has to handle null.
     */
    val project: DiscoveredProject? = null,
) {
    // Browsing currently supports the IDE host only. Physical checkouts must remain distinct,
    // even when discovery groups their Git remotes under the same logical project identity.
    val key: String get() = "host:${scope.name}:${
        when {
            scope != SkillScope.PROJECT -> ""
            project != null -> projectPath(project)
            else -> "*all*"
        }
    }"

    companion object {
        fun projectPath(project: DiscoveredProject): String = project.path ?: project.gitRoot.orEmpty()
    }
}

internal data class SkillBrowserSnapshot(
    val context: SkillBrowserContext,
    val skills: List<AgentSkill>,
    val warnings: List<EnvironmentWarning> = emptyList(),
    val targetStatuses: Map<String, List<ObservedSkillTarget>> = emptyMap(),
    val sourceFiles: Map<String, List<String>> = emptyMap(),
    val backupSkillIds: Set<String> = emptySet(),
    val sourceStats: Map<String, SourceStat> = emptyMap(),
    /**
     * Defaults to the ordinary per-[context] derivation, so every plain constructor call (tests
     * included) gets correct rows for free. An "all projects" snapshot has no single project to
     * derive rows from that way (its own [context] carries a null project), so
     * [SkillBrowserDiscovery] overrides this explicitly there, concatenating several concrete
     * per-project snapshots' already-correct rows instead - each keeping that project's own
     * context for mutations.
     */
    val rows: List<SkillOccurrenceRow> = SkillBrowserModel.rows(context, skills),
)

/**
 * File count and total size of a [SkillSource]'s directory, so the Overview page's Sources list
 * can show more than a bare path — in particular, whether an agent-specific source is a real,
 * independent copy or just a link (a Windows junction resolves as a plain directory to the JDK,
 * so [isLink] is detected separately from the size/count, which reflect the link's target either way).
 */
internal data class SourceStat(
    val fileCount: Int,
    val totalSizeBytes: Long,
    val isLink: Boolean,
    val truncated: Boolean = false,
)

internal enum class SkillBrowserFilter(val label: String) {
    ALL("All"), SHARED("Shared"), CONFLICTS("Conflicts"), SYSTEM("System"),
}

/**
 * Coarse ownership signal for a single occurrence row, cheap enough to evaluate for every row on
 * every render: it only asks the in-memory/persisted [com.shutterstar.agenthub.environment.skills.sync.ownership.SyncOwnershipStore]
 * whether *this* row's agent copy is a recorded managed target (see the `isManaged` callback
 * threaded through [SkillBrowserModel.filter]) — it does not re-verify the on-disk fingerprint the
 * way [com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget.owner] does
 * for a single selected skill, since that requires reading files and would turn a list-wide filter
 * into a filesystem walk over every shared skill's every target. A shared/canonical source has no
 * `agentId` of its own and therefore always falls under [UNMANAGED] here; use the existing
 * [SkillBrowserFilter.SHARED] state filter to isolate canonical rows instead.
 */
internal enum class SkillOwnershipFilter(val label: String) {
    ALL("All ownership"), MANAGED("AgentHub-managed"), UNMANAGED("Not managed"),
}

/**
 * First-slice "Shared Skills dashboard" (spec §91): per-skill-group counts, cheap to compute from
 * already-discovered data (no extra filesystem/ownership-store calls per skill). Deliberately
 * excludes a "broken" count and per-agent coverage — those need the same live target-state
 * observation [com.shutterstar.agenthub.environment.skills.sync.SkillSyncApplicationService.managedTargetIds]
 * currently only runs for one selected skill at a time, not batched over an entire scope; running
 * it for every shared skill just to render this line would turn a list refresh into a filesystem
 * walk per skill. Also skips a synthesized health percentage per the spec's own later note ("Ne
 * jelenjen meg önkényesen választott egészségszázalék").
 */
internal data class SkillDashboardSummary(
    val sharedSkillCount: Int,
    val inSyncCount: Int,
    val conflictCount: Int,
) {
    val label: String get() = listOfNotNull(
        sharedSkillCount.takeIf { it > 0 }?.let { "$it shared" },
        inSyncCount.takeIf { it > 0 }?.let { "$it in sync" },
        conflictCount.takeIf { it > 0 }?.let { count -> "$count " + if (count == 1) "conflict" else "conflicts" },
    ).joinToString(" · ")
}

internal data class SkillOccurrenceRow(
    val key: String,
    val title: String,
    val skill: AgentSkill,
    val source: SkillSource,
    val context: SkillBrowserContext,
    /** The agents the skill folder belongs to (in a project: only those that have worked on the project). */
    val agentIds: Set<String>,
    val contextLabel: String,
    /** Agents that merely read this folder because it belongs to another agent. */
    val readerAgentIds: Set<String> = emptySet(),
) {
    val stateLabel: String get() = when (skill.consistency) {
        SkillConsistency.DIFFERENT -> "Different contents"
        SkillConsistency.IDENTICAL -> "Identical contents"
        SkillConsistency.SINGLE_SOURCE -> "Single source"
    }
    val sourceLabel: String get() = when {
        source.system -> "System"
        source.shared -> "Shared source"
        else -> "Agent-specific"
    }
    val skillFile: Path? get() = runCatching { Path.of(source.path).resolve("SKILL.md") }.getOrNull()
}

internal object SkillBrowserModel {
    fun dashboardSummary(skills: List<AgentSkill>): SkillDashboardSummary = SkillDashboardSummary(
        sharedSkillCount = skills.count { skill -> skill.sources.any(SkillSource::shared) },
        inSyncCount = skills.count { it.consistency == SkillConsistency.IDENTICAL },
        conflictCount = skills.count { it.consistency == SkillConsistency.DIFFERENT },
    )

    /**
     * The user's skills by name first; the vendor-shipped/synced (system) ones after them, grouped by the agent they
     * belong to and by name within it. Ties break by path, so the order never depends on how the rows were collected.
     */
    private val rowOrder = compareBy<SkillOccurrenceRow> { it.source.system }
        .thenBy { row -> if (row.source.system) systemOwner(row) else "" }
        .thenBy { it.title.lowercase(Locale.ROOT) }
        .thenBy { it.source.path }

    private fun systemOwner(row: SkillOccurrenceRow): String =
        (SkillRootOwner.ownerOf(row.source.path) ?: row.source.agentId ?: row.agentIds.firstOrNull() ?: "").lowercase(Locale.ROOT)

    /** Kept for tests/callers that already have a snapshot in hand; delegates to the (context, skills) overload. */
    fun rows(snapshot: SkillBrowserSnapshot): List<SkillOccurrenceRow> = rows(snapshot.context, snapshot.skills)

    fun rows(context: SkillBrowserContext, skills: List<AgentSkill>): List<SkillOccurrenceRow> = skills.flatMap { skill ->
        skill.sources.filter { it.scope == context.scope }
            // Multiple providers can discover the same compatibility directory. Show its physical
            // occurrence once, while retaining all discovering agents as icons in the secondary row.
            .groupBy { it.path }
            .map { (path, sources) ->
                val source = sources.firstOrNull { it.shared } ?: sources.first()
                val (owners, readers) = ownersAndReaders(sources, context, skill)
                SkillOccurrenceRow(
                    key = "${context.key}:${skill.identity.id}:$path",
                    title = source.displayTitle?.takeIf(String::isNotBlank) ?: skill.name,
                    skill = skill,
                    source = source,
                    context = context,
                    agentIds = owners,
                    contextLabel = if (context.scope == SkillScope.GLOBAL) "Global" else
                        "${context.project!!.name} · Project",
                    readerAgentIds = readers,
                )
            }
    }.sortedWith(rowOrder)

    /**
     * Owners are the agents whose own folder this is; when none of them is known (the owner is not installed), the
     * agents that read it stand in. In a project the owner of a folder is always shown, but an agent that merely reads
     * the folder only when it has a session there, so it never shows up for a project it has nothing to do with: the
     * shared `.agents/skills` folder is shown with the project's agents that read it.
     */
    private fun ownersAndReaders(
        sources: List<SkillSource>,
        context: SkillBrowserContext,
        skill: AgentSkill,
    ): Pair<Set<String>, Set<String>> {
        val inScope = context.project?.takeIf { context.scope == SkillScope.PROJECT }
            ?.let { project -> project.agents.mapTo(hashSetOf()) { it.agentId } }
        val candidates = sources.filter { it.agentId != null }
        val native = candidates.filter { it.native }.mapNotNullTo(sortedSetOf()) { it.agentId }
        val readers = candidates.filterNot { it.native }.mapNotNullTo(sortedSetOf()) { it.agentId }
        if (inScope == null) {
            val owners = native.ifEmpty { readers }
            return owners to (readers - owners)
        }
        val sharedReaders = if (sources.any { it.shared }) {
            skill.compatibleAgents.filterTo(sortedSetOf()) { it in AgentCapabilityRegistry.agentIdsSupportingSharedSkills() }
        } else {
            emptySet()
        }
        // The agent a folder belongs to is shown even without a session (there are many reasons for having none); only
        // the agents that merely read a folder have to have worked on the project.
        val owners = (native + sharedReaders.filterTo(sortedSetOf()) { it in inScope }).toSortedSet()
            .ifEmpty { readers.filterTo(sortedSetOf()) { it in inScope } }
        return owners to (readers - owners)
    }

    /**
     * Folds a skill's linked occurrences (a symlink/junction into another location - almost
     * always the shared source, since that's what sync creates) into the one row that actually
     * holds the content, instead of listing every agent's link as its own row: a skill shared
     * with N agents by link would otherwise show up N(+1) times, once per distinct filesystem
     * path, even though only one of those paths has real content - the rest are just where an
     * agent finds it (that per-agent coverage belongs to the row's own Agents detail tab, not the
     * top-level list). [isLink] is a path lookup because link-ness is a filesystem fact
     * ([SourceStat.isLink]), not something [rows] can know from [AgentSkill]/[SkillSource] alone.
     * A group with no non-linked row left as-is (nothing to fold into) rather than dropped.
     */
    fun mergeLinkedOccurrences(rows: List<SkillOccurrenceRow>, isLink: (String) -> Boolean): List<SkillOccurrenceRow> =
        rows.groupBy { it.context.key to it.skill.identity.id }.values.flatMap { group ->
            val (linked, real) = group.partition { isLink(it.source.path) }
            if (real.isEmpty()) return@flatMap group
            val primary = real.firstOrNull { it.source.shared } ?: real.first()
            // A shared source also shows the agents that hold an independent copy of it (they have the skill just as
            // much as the ones that link it); a copy keeps its own row too. A non-shared primary takes only its links.
            val copies = if (primary.source.shared) real - primary else emptyList()
            if (linked.isEmpty() && copies.isEmpty()) return@flatMap group
            val folded = linked + copies
            val mergedAgentIds = (primary.agentIds + folded.flatMap { it.agentIds }).toSortedSet()
            val mergedReaderIds = (primary.readerAgentIds + folded.flatMap { it.readerAgentIds }).toSortedSet() - mergedAgentIds
            listOf(primary.copy(agentIds = mergedAgentIds, readerAgentIds = mergedReaderIds)) + (real - primary)
        }.sortedWith(rowOrder)

    fun filter(
        rows: List<SkillOccurrenceRow>,
        query: String,
        filter: SkillBrowserFilter,
        agentId: String?,
        ownership: SkillOwnershipFilter = SkillOwnershipFilter.ALL,
        isManaged: (SkillOccurrenceRow) -> Boolean = { false },
    ): List<SkillOccurrenceRow> {
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty)
        return rows.filter { row ->
            val matchesState = when (filter) {
                SkillBrowserFilter.ALL -> true
                SkillBrowserFilter.SHARED -> row.source.shared
                SkillBrowserFilter.CONFLICTS -> row.skill.consistency == SkillConsistency.DIFFERENT
                SkillBrowserFilter.SYSTEM -> row.source.system
            }
            val matchesOwnership = when (ownership) {
                SkillOwnershipFilter.ALL -> true
                SkillOwnershipFilter.MANAGED -> isManaged(row)
                SkillOwnershipFilter.UNMANAGED -> !isManaged(row)
            }
            val text = listOf(row.title, row.skill.name, row.source.path, row.contextLabel, row.skill.description.orEmpty())
                .joinToString(" ").lowercase(Locale.ROOT)
            matchesState && matchesOwnership && (agentId == null || agentId in row.agentIds) && terms.all { it in text }
        }
    }
}
