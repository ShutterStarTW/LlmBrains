package com.shutterstar.agenthub.environment.skills.ui

import com.shutterstar.agenthub.environment.skills.discovery.SkillDiscoveryService
import com.shutterstar.agenthub.environment.skills.model.SkillScope
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.environment.skills.model.AgentSkill
import com.shutterstar.agenthub.environment.skills.sync.planning.ObservedSkillTarget
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean

/** Skills-only discovery: does not require sessions or scan unrelated MCP/instruction configs. */
internal class SkillBrowserDiscovery(
    private val discovery: SkillDiscoveryService = SkillDiscoveryService(),
    private val targetStatuses: (AgentSkill, SkillBrowserContext) -> List<ObservedSkillTarget> = { _, _ -> emptyList() },
    private val backupSkillIds: (List<AgentSkill>, SkillBrowserContext) -> Set<String> = { _, _ -> emptySet() },
    /** Supplies the candidate list for a [context] with [SkillBrowserContext.project] == null ("all projects"). */
    private val projectsWithSkills: () -> List<DiscoveredProject> = { emptyList() },
) {
    fun discover(context: SkillBrowserContext): SkillBrowserSnapshot {
        if (context.scope == SkillScope.PROJECT && context.project == null) return discoverAllProjects(context)
        val (records, warnings) = when (context.scope) {
            SkillScope.GLOBAL -> discovery.discoverGlobalRecordsWithWarnings()
            SkillScope.PROJECT -> discovery.discoverProjectRecordsWithWarnings(requireNotNull(context.project))
        }
        val skills = discovery.normalize(records)
        // rows is left to its default (derived from context + skills) here.
        return SkillBrowserSnapshot(
            context,
            skills,
            warnings,
            skills.filter { it.sources.any { source -> source.shared } }
                .associate { it.identity.id to targetStatuses(it, context) },
            skills.flatMap { it.sources }.associate { source -> source.path to findFiles(source.path) },
            backupSkillIds(skills, context),
            skills.flatMap { it.sources }.mapNotNull { source -> computeStat(source.path)?.let { source.path to it } }.toMap(),
        )
    }

    /**
     * Runs the ordinary per-project [discover] for every project known to have a skill and
     * concatenates the results. Each sub-snapshot's rows already carry that project's own,
     * concrete [SkillBrowserContext] - reusing them (rather than re-deriving rows from a merged
     * skill list against this aggregate [context]) is what lets Share/Promote/Resync keep working
     * on an "all projects" row without ever seeing a null project.
     */
    private fun discoverAllProjects(context: SkillBrowserContext): SkillBrowserSnapshot {
        val perProject = projectsWithSkills().map { discover(SkillBrowserContext(SkillScope.PROJECT, it)) }
        return SkillBrowserSnapshot(
            context = context,
            skills = perProject.flatMap { it.skills },
            warnings = perProject.flatMap { it.warnings },
            targetStatuses = perProject.map { it.targetStatuses }
                .fold(emptyMap<String, List<ObservedSkillTarget>>()) { acc, map -> acc + map },
            sourceFiles = perProject.map { it.sourceFiles }
                .fold(emptyMap<String, List<String>>()) { acc, map -> acc + map },
            backupSkillIds = perProject.flatMapTo(mutableSetOf()) { it.backupSkillIds },
            sourceStats = perProject.map { it.sourceStats }
                .fold(emptyMap<String, SourceStat>()) { acc, map -> acc + map },
            rows = perProject.flatMap { it.rows },
        )
    }

    /** Used to narrow the project picker to projects that actually have a discoverable skill. */
    fun hasSkills(project: DiscoveredProject): Boolean = discovery.discoverProjectRecords(project).isNotEmpty()

    /** Bounded, read-only listing of every file under a source - shown in the UI, never opened or executed. */
    private fun findFiles(sourcePath: String): List<String> {
        val root = runCatching { Path.of(sourcePath) }.getOrNull() ?: return emptyList()
        if (!Files.isDirectory(root)) return emptyList()
        return runCatching {
            Files.walk(root, 4).use { paths ->
                paths.filter(Files::isRegularFile).limit(200)
                    .map { root.relativize(it).toString() }
                    .sorted().toList()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * A Windows junction reports `isSymbolicLink() == false` (the JDK only recognizes
     * `IO_REPARSE_TAG_SYMLINK`, not the `IO_REPARSE_TAG_MOUNT_POINT` tag junctions use — see
     * [com.shutterstar.agenthub.environment.skills.sync.execution.DirectoryDeleter]), so it is
     * detected here as [BasicFileAttributes.isOther] instead. The count/size below still walk
     * through the link (Windows transparently resolves it), reflecting the target's real content.
     */
    private fun computeStat(sourcePath: String): SourceStat? {
        val root = runCatching { Path.of(sourcePath) }.getOrNull() ?: return null
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return null
        val isLink = Files.isSymbolicLink(root) ||
            runCatching {
                Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isOther
            }.getOrDefault(false)
        if (!Files.isDirectory(root)) return SourceStat(0, 0, isLink)

        return runCatching {
            Files.walk(root, MAX_STAT_DEPTH + 1).use { paths ->
                val depthLimited = AtomicBoolean(false)
                val files = paths.filter { path ->
                    if (root.relativize(path).nameCount > MAX_STAT_DEPTH) {
                        if (Files.isRegularFile(path) ||
                            (Files.isDirectory(path) && Files.newDirectoryStream(path).use { it.iterator().hasNext() })
                        ) {
                            depthLimited.set(true)
                        }
                        false
                    } else {
                        Files.isRegularFile(path)
                    }
                }.limit(MAX_STAT_ENTRIES + 1L).toList()
                val truncated = files.size > MAX_STAT_ENTRIES || depthLimited.get()
                val counted = files.take(MAX_STAT_ENTRIES)
                SourceStat(
                    fileCount = counted.size,
                    totalSizeBytes = counted.sumOf { file -> runCatching { Files.size(file) }.getOrDefault(0L) },
                    isLink = isLink,
                    truncated = truncated,
                )
            }
        }.getOrDefault(SourceStat(0, 0, isLink))
    }

    private companion object {
        const val MAX_STAT_DEPTH = 12
        const val MAX_STAT_ENTRIES = 2000
    }
}
