package com.shutterstar.agenthub.projects.ui

import com.shutterstar.agenthub.projects.launch.NativeResumeCommands
import com.shutterstar.agenthub.projects.model.AgentProject
import com.shutterstar.agenthub.projects.model.AgentSession
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectComparators
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * One row of the flat Sessions list: either a collapsible group header (an agent, in project
 * context; a project, in agent context) or one session underneath an expanded group.
 */
internal sealed interface SessionRowItem {
    val groupKey: String

    data class Group(
        override val groupKey: String,
        /** Set in project context (the group *is* an agent), null in agent context (the group is a project). */
        val agentId: String?,
        val title: String,
        val detail: String,
        val sessionCount: Int,
        val expanded: Boolean,
    ) : SessionRowItem

    data class Session(
        override val groupKey: String,
        val session: AgentSession,
        val title: String,
        val timeLabel: String,
        /** Message count and/or [timeLabel], whichever isn't already shown by [title]; null if there is nothing to add. */
        val detail: String?,
        /** Whether AgentHub knows a native resume command for this session (see [NativeResumeCommands]). */
        val resumable: Boolean,
        /** Why [resumable] is false (unsupported agent, missing or unsafe ID); null when resumable. */
        val resumeBlockedReason: String? = null,
    ) : SessionRowItem
}

/**
 * Pure (no Swing, no IntelliJ) builder for the Sessions list. Groups are ordered by most recent
 * activity, sessions within a group by recency; a collapsed group contributes only its header.
 */
internal object SessionListModel {
    fun byAgent(
        project: DiscoveredProject,
        expandedKeys: Set<String>,
        agentName: (String) -> String,
        formatTime: (Instant) -> String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SessionRowItem> = project.agents
        .sortedWith(compareByDescending<AgentProject> { it.lastActivity ?: Instant.MIN }.thenBy { it.agentId })
        .flatMap { relation ->
            val key = "agent:${relation.agentId}"
            group(
                key = key,
                agentId = relation.agentId,
                title = agentName(relation.agentId),
                sessionCount = relation.sessionCount,
                lastActivity = relation.lastActivity,
                sessions = relation.sessions,
                expanded = key in expandedKeys,
                formatTime = formatTime,
                zone = zone,
            )
        }

    fun byProject(
        agentId: String,
        projects: List<DiscoveredProject>,
        expandedKeys: Set<String>,
        formatTime: (Instant) -> String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SessionRowItem> = projects
        .mapNotNull { project -> project.agents.firstOrNull { it.agentId == agentId }?.let { project to it } }
        .sortedWith(
            compareByDescending<Pair<DiscoveredProject, AgentProject>> { (_, relation) -> relation.lastActivity ?: Instant.MIN }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { (project, _) -> project.name },
        )
        .flatMap { (project, relation) ->
            val key = "project:${project.identity.id}"
            group(
                key = key,
                agentId = null,
                title = project.name,
                sessionCount = relation.sessionCount,
                lastActivity = relation.lastActivity,
                sessions = relation.sessions,
                expanded = key in expandedKeys,
                formatTime = formatTime,
                zone = zone,
            )
        }

    /** The default expansion for a freshly selected entity: only the most recently active group open. */
    fun defaultExpandedKeys(items: List<SessionRowItem>): Set<String> =
        setOfNotNull(items.firstOrNull { it is SessionRowItem.Group }?.groupKey)

    /**
     * An agent-provided title names the session; the user's first message is the fallback, and a
     * session with neither shows its time range. Keep titles to one visible line; the renderer's
     * tooltip provides the full text when the list clips it.
     */
    fun sessionTitle(session: AgentSession, timeLabel: String): String =
        sequenceOf(session.title, session.firstMessage)
            .filterNotNull()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .firstOrNull(String::isNotEmpty)
            ?: EMPTY_SESSION_TITLE.takeIf { session.messageCount == 0 }
            ?: timeLabel

    private const val EMPTY_SESSION_TITLE = "Empty session"

    /**
     * "2026. 04. 20. 08:05 - 09:05" within one day, "2026. 04. 20. 10:34 - 04. 21. 01:55" across
     * days, and the full date on both sides across years. A single known (or minute-identical)
     * moment is shown alone.
     */
    fun dateRangeLabel(startedAt: Instant?, endedAt: Instant?, zone: ZoneId): String {
        val start = startedAt?.atZone(zone)?.truncatedTo(ChronoUnit.MINUTES)
        val end = endedAt?.atZone(zone)?.truncatedTo(ChronoUnit.MINUTES)
        if (start == null || end == null || start == end) {
            return (end ?: start)?.format(FULL) ?: "Unknown time"
        }
        val (from, to) = if (start.isAfter(end)) end to start else start to end
        val toFormat = when {
            from.toLocalDate() == to.toLocalDate() -> TIME_ONLY
            from.year == to.year -> MONTH_DAY_TIME
            else -> FULL
        }
        return "${from.format(FULL)} - ${to.format(toFormat)}"
    }

    private val FULL = DateTimeFormatter.ofPattern("yyyy. MM. dd. HH:mm")
    private val MONTH_DAY_TIME = DateTimeFormatter.ofPattern("MM. dd. HH:mm")
    private val TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * What the second line of a session row should add beyond the title: the message count and/or
     * the date range, whichever of those isn't already the title itself. Null means there is nothing
     * left to say (no title beyond the range, and no known message count).
     */
    private fun sessionDetail(title: String, rangeLabel: String, messageCount: Int?): String? {
        val hasRealTitle = title != rangeLabel
        val countLabel = messageCount?.takeIf { it > 0 }?.let(::messageCountLabel)
        return when {
            hasRealTitle && countLabel != null -> "$countLabel · $rangeLabel"
            hasRealTitle -> rangeLabel
            countLabel != null -> countLabel
            else -> null
        }
    }

    /** The count is of the user's own messages only, hence "prompts". */
    private fun messageCountLabel(count: Int): String = if (count == 1) "1 prompt" else "$count prompts"

    private fun group(
        key: String,
        agentId: String?,
        title: String,
        sessionCount: Int,
        lastActivity: Instant?,
        sessions: List<AgentSession>,
        expanded: Boolean,
        formatTime: (Instant) -> String,
        zone: ZoneId,
    ): List<SessionRowItem> {
        val activity = lastActivity?.let(formatTime) ?: "Unknown"
        val header = SessionRowItem.Group(
            groupKey = key,
            agentId = agentId,
            title = title,
            detail = "${sessionLabel(sessionCount)} · Last activity: $activity",
            sessionCount = sessionCount,
            expanded = expanded && sessions.isNotEmpty(),
        )
        if (!header.expanded) return listOf(header)
        return listOf(header) + sessions
            .sortedWith(ProjectComparators.agentSessionByRecency)
            .map { session ->
                val rangeLabel = dateRangeLabel(session.startedAt, session.updatedAt, zone)
                val title = sessionTitle(session, rangeLabel)
                SessionRowItem.Session(
                    groupKey = key,
                    session = session,
                    title = title,
                    timeLabel = rangeLabel,
                    detail = sessionDetail(title, rangeLabel, session.messageCount),
                    resumable = NativeResumeCommands.command(session.agentId, session.nativeResumeId) != null,
                    resumeBlockedReason = NativeResumeCommands.unavailableReason(session.agentId, session.nativeResumeId),
                )
            }
    }

    private fun sessionLabel(count: Int): String = if (count == 1) "1 session" else "$count sessions"
}
