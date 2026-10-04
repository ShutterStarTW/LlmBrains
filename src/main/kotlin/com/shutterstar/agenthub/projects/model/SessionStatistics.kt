package com.shutterstar.agenthub.projects.model

/** Only allowlisted numbers and model identifiers; never transcript content or tool arguments. */
object SessionStatistics {
    val labels: Map<String, String> = linkedMapOf(
        "models" to "Models",
        "inputTokens" to "Input tokens",
        "cachedTokens" to "Cache read tokens",
        "cacheWriteTokens" to "Cache write tokens",
        "outputTokens" to "Output tokens",
        "reasoningTokens" to "Reasoning tokens (included in output)",
        "totalTokens" to "Total tokens",
        "modelCalls" to "Model responses with usage",
        "toolCalls" to "Tool calls",
        "toolErrors" to "Failed tool calls",
        "editTurns" to "Prompts with file edits",
        "subagentCalls" to "Subagent calls",
        "repeatedPrompts" to "Consecutive repeated prompts",
        "apiMillis" to "API time",
        "reportedCostTicks" to "Recorded cost (USD)",
        "partialRecordedCostTicks" to "Recorded cost (partial, USD)",
        "contextUsageBasisPoints" to "Context utilization",
        "nanoAiu" to "Usage (nano AI units)",
        "premiumRequests" to "Premium requests",
        "linesAdded" to "Lines added",
        "linesRemoved" to "Lines removed",
        "filesModified" to "Files modified",
        "modelErrors" to "Failed model calls",
        "incompleteUsageRecords" to "Incomplete usage records",
        "activeMillis" to "Active time (gaps over 30 minutes excluded)",
        "elapsedMillis" to "Elapsed time",
        "firstEventMillis" to "Started",
        "lastEventMillis" to "Last recorded activity",
        "contextTokens" to "Last context tokens",
        "contextWindow" to "Context window",
    )

    fun sanitize(values: Map<String, String>): Map<String, String> = values.filter { (key, value) ->
        key in labels && if (key == "models") {
            value.isNotBlank() && value.length <= 1024 && value.all { it.isLetterOrDigit() || it in "-._:/, " }
        } else {
            value.toLongOrNull()?.let { it >= 0 } == true
        }
    }.toMap()
}
