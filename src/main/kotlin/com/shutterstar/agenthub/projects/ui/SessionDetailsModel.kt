package com.shutterstar.agenthub.projects.ui

import com.shutterstar.agenthub.projects.model.AgentSession
import com.shutterstar.agenthub.projects.model.SessionStatistics
import java.time.Duration
import java.time.Instant
import java.text.NumberFormat

internal object SessionDetailsModel {
    fun lines(session: AgentSession, formatTime: (Instant) -> String): List<String> = buildList {
        session.title?.takeIf { it.isNotBlank() }?.let { add(it) }
        add("Session: ${session.id}")
        session.projectPath?.takeIf { it.isNotBlank() }?.let { add("Project: $it") }
        session.messageCount?.takeIf { it > 0 }?.let { add("Prompts: $it") }
        val visible = visibleStatistics(session)
        if ("firstEventMillis" !in visible) session.startedAt?.let { add("Started: ${formatTime(it)}") }
        if ("lastEventMillis" !in visible) session.updatedAt?.let { add("Last activity: ${formatTime(it)}") }
        visible.let { statistics ->
            SessionStatistics.labels.forEach { (key, label) ->
                statistics[key]?.let { value ->
                    val formatted = when (key) {
                        "models" -> value
                        "firstEventMillis", "lastEventMillis" -> formatTime(Instant.ofEpochMilli(value.toLong()))
                        "activeMillis", "elapsedMillis", "apiMillis" -> duration(value.toLong())
                        "reportedCostTicks", "partialRecordedCostTicks" -> java.math.BigDecimal(value).movePointLeft(10).stripTrailingZeros().toPlainString()
                        "contextUsageBasisPoints" -> "${java.math.BigDecimal(value).movePointLeft(2).stripTrailingZeros().toPlainString()}%"
                        else -> NumberFormat.getIntegerInstance().format(value.toLong())
                    }
                    val actualLabel = if (key == "inputTokens" && session.agentId in setOf("codex", "qwen", "cline")) "Input tokens (includes cache)" else label
                    add("$actualLabel: $formatted")
                }
            }
        }
        session.sourcePath?.takeIf { it.isNotBlank() }?.let { add("Transcript: $it") }
    }

    fun summary(session: AgentSession): String? {
        val statistics = visibleStatistics(session)
        return listOfNotNull(
            statistics["models"],
            statistics["totalTokens"]?.toLongOrNull()?.let { "${NumberFormat.getIntegerInstance().format(it)} tokens" },
            statistics["activeMillis"]?.toLongOrNull()?.let { "${duration(it)} active" },
        ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun visibleStatistics(session: AgentSession): Map<String, String> =
        SessionStatistics.sanitize(session.statistics).filter { (key, value) -> key == "models" || value.toLong() > 0 }

    private fun duration(millis: Long): String {
        val duration = Duration.ofMillis(millis)
        return if (duration.toHours() > 0) "${duration.toHours()}h ${duration.toMinutesPart()}m ${duration.toSecondsPart()}s"
        else "${duration.toMinutes()}m ${duration.toSecondsPart()}s"
    }
}
