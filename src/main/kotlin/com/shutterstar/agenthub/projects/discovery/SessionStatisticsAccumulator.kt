package com.shutterstar.agenthub.projects.discovery

import com.shutterstar.agenthub.projects.model.SessionStatistics
import java.time.Instant
import java.security.MessageDigest

/** Accumulates the agent's own usage records in the existing transcript scan. */
internal class SessionStatisticsAccumulator(private val agentId: String) {
    private val values = linkedMapOf<String, Long>()
    private val models = linkedSetOf<String>()
    private val messages = hashSetOf<String>()
    private val tools = hashSetOf<String>()
    private var first: Instant? = null
    private var last: Instant? = null
    private var recordedStart: Instant? = null
    private var recordedEnd: Instant? = null
    private var previous: Instant? = null
    private var hasPrompt = false
    private var edited = false
    private var cumulativeUsage = false
    private var previousPrompt: List<Byte>? = null
    private var grokTurnOpen = false
    private var grokUsageEvents = 0
    private var grokCostEvents = 0
    private var grokCostIncomplete = false
    private val additional = AdditionalSessionStatisticsReader(this, agentId)

    fun userPrompt(text: String? = null) {
        finishTurn()
        hasPrompt = true
        text?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }?.let {
            values.putIfAbsent("repeatedPrompts", 0)
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)).toList()
            if (fingerprint == previousPrompt) increment("repeatedPrompts", 1)
            previousPrompt = fingerprint
        }
    }

    fun record(line: String) {
        val fields = MetadataJsonParser.topLevelStringFields(line, setOf("type", "timestamp", "requestId"))
        LocalSessionSupport.parseTimestamp(fields["timestamp"])?.let(::timestamp)
        when (agentId) {
            "claude" -> if (fields["type"] == "assistant") claude(line, fields["requestId"])
            "codex" -> codex(line, fields["type"])
            "grok" -> grok(line)
            else -> additional.record(line)
        }
    }

    private fun grok(line: String) {
        val metaTime = MetadataJsonParser.rawPath(line, "params", "_meta")?.let {
            MetadataJsonParser.topLevelLongFields(it, setOf("agentTimestampMs"))["agentTimestampMs"]
        }
        if (metaTime != null && metaTime > 0) timestamp(Instant.ofEpochMilli(metaTime))
        else MetadataJsonParser.rawTopLevelField(line, "timestamp")?.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }?.let {
            timestamp(Instant.ofEpochMilli((if (it > 1e12) it else it * 1000).toLong()))
        }
        val update = MetadataJsonParser.rawPath(line, "params", "update") ?: return
        val fields = MetadataJsonParser.topLevelStringFields(update, setOf("sessionUpdate", "title", "toolCallId", "status"))
        when (fields["sessionUpdate"]) {
            "user_message_chunk" -> if (!grokTurnOpen) {
                userPrompt()
                grokTurnOpen = true
            }
            "tool_call" -> tool(MetadataJsonParser.stringAtPath(update, "_meta", "x.ai/tool", "name") ?: fields["title"], fields["toolCallId"])
            "tool_call_update" -> if (fields["status"] == "failed") increment("toolErrors", 1)
            "turn_completed" -> {
                finishTurn()
                hasPrompt = false
                grokTurnOpen = false
                grokUsageEvents++
                val usage = MetadataJsonParser.rawTopLevelField(update, "usage") ?: return
                val numbers = MetadataJsonParser.topLevelLongFields(usage, GROK_TOKENS)
                if (numbers.isEmpty()) return
                fun number(vararg keys: String): Long? = keys.firstNotNullOfOrNull { numbers[it]?.takeIf { n -> n >= 0 } }
                val input = number("inputTokens", "input_tokens")
                val cached = number("cachedReadTokens", "cacheReadInputTokens", "cache_read_input_tokens", "cached_input_tokens")
                val written = number("cacheCreationTokens", "cachedWriteTokens", "cacheWriteInputTokens", "cache_creation_input_tokens")
                val output = number("outputTokens", "output_tokens")
                val normalized = buildMap {
                    input?.let { put("input_tokens", if ("inputTokens" in numbers) (it - (cached ?: 0) - (written ?: 0)).coerceAtLeast(0) else it) }
                    cached?.let { put("cache_read_input_tokens", it) }
                    written?.let { put("cache_creation_input_tokens", it) }
                    output?.let { put("output_tokens", it) }
                    number("reasoningTokens", "reasoning_output_tokens")?.let { put("reasoning_output_tokens", it.coerceAtMost(output ?: 0)) }
                    number("totalTokens", "total_tokens")?.let { put("total_tokens", it) }
                }
                addTokens(normalized, false, false)
                number("modelCalls", "model_calls")?.let { increment("modelCalls", it) }
                number("apiDurationMs", "api_duration_ms")?.let { increment("apiMillis", it) }
                MetadataJsonParser.rawTopLevelField(usage, "modelUsage")?.let {
                    MetadataJsonParser.objectEntries(it).orEmpty().forEach { (name, _) -> model(name) }
                }
                val flags = MetadataJsonParser.topLevelBooleanFields(usage, setOf("usageIsIncomplete", "usage_is_incomplete", "costIsPartial", "cost_is_partial"))
                if (flags["usageIsIncomplete"] == true || flags["usage_is_incomplete"] == true) increment("incompleteUsageRecords", 1)
                grokCostIncomplete = grokCostIncomplete || flags.values.any { it }
                val ticks = number("costUsdTicks", "totalCostUsdTicks", "cost_usd_ticks", "total_cost_usd_ticks")
                    ?: listOf("costUsd", "totalCostUsd", "cost_usd", "total_cost_usd").firstNotNullOfOrNull {
                        MetadataJsonParser.rawTopLevelField(usage, it)?.toBigDecimalOrNull()?.takeIf { n -> n.signum() >= 0 }
                            ?.movePointRight(10)?.runCatching { longValueExact() }?.getOrNull()
                    }
                ticks?.let { grokCostEvents++; increment("reportedCostTicks", it) }
            }
        }
    }

    fun timestamp(time: Instant) {
        if (first == null || time < first) first = time
        if (last == null || time > last) last = time
        previous?.let { prior ->
            val delta = time.toEpochMilli() - prior.toEpochMilli()
            if (delta in 1..1_800_000) increment("activeMillis", delta)
        }
        if (previous == null || time > previous) previous = time
    }

    fun model(value: String?) {
        value?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("<synthetic>", "synthetic", "unknown", "<unknown>") }
            ?.takeIf { models.size < 32 }?.let(models::add)
    }

    private fun claude(line: String, requestId: String?) {
        val message = MetadataJsonParser.rawTopLevelField(line, "message") ?: return
        val fields = MetadataJsonParser.topLevelStringFields(message, setOf("id", "model"))
        model(fields["model"])
        // A streamed model response may occupy several transcript records with the same usage.
        val key = fields["id"]?.let { "$it:${requestId.orEmpty()}" }
        val usage = MetadataJsonParser.rawTopLevelField(message, "usage")
        if (usage != null) {
            val tokens = MetadataJsonParser.topLevelLongFields(usage, CLAUDE_TOKENS)
            if (tokens.isNotEmpty() && (key == null || messages.add(key))) {
                increment("modelCalls", 1)
                addTokens(tokens, false, false)
                values["contextTokens"] = (tokens["input_tokens"] ?: 0) + (tokens["cache_read_input_tokens"] ?: 0) + (tokens["cache_creation_input_tokens"] ?: 0)
                val reasoning = MetadataJsonParser.rawTopLevelField(usage, "output_tokens_details")?.let {
                    MetadataJsonParser.topLevelLongFields(it, setOf("thinking_tokens", "reasoning_tokens"))
                }.orEmpty()
                (reasoning["thinking_tokens"] ?: reasoning["reasoning_tokens"])?.let {
                    increment("reasoningTokens", it.coerceAtMost(tokens["output_tokens"] ?: 0))
                }
            }
        }
        val content = MetadataJsonParser.rawTopLevelField(message, "content") ?: return
        MetadataJsonParser.arrayElements(content).orEmpty().forEach { block ->
            val tool = MetadataJsonParser.topLevelStringFields(block, setOf("type", "id", "name"))
            if (tool["type"] == "tool_use") tool(tool["name"], tool["id"])
        }
    }

    private fun codex(line: String, type: String?) {
        val payload = MetadataJsonParser.rawTopLevelField(line, "payload") ?: return
        val fields = MetadataJsonParser.topLevelStringFields(payload, setOf("type", "model", "name", "call_id"))
        if (type == "turn_context") model(fields["model"])
        if (type == "response_item" && fields["type"] in setOf("function_call", "custom_tool_call")) {
            tool(fields["name"], fields["call_id"])
        }
        if (type != "event_msg" || fields["type"] != "token_count") return
        val info = MetadataJsonParser.rawTopLevelField(payload, "info") ?: return
        val total = MetadataJsonParser.rawTopLevelField(info, "total_token_usage")
        val recent = MetadataJsonParser.rawTopLevelField(info, "last_token_usage")
        // Codex reports cumulative counters repeatedly, including duplicates after tool events.
        if (total != null) {
            cumulativeUsage = true
            addTokens(MetadataJsonParser.topLevelLongFields(total, CODEX_TOKENS), true, true)
        } else if (!cumulativeUsage && recent != null) {
            addTokens(MetadataJsonParser.topLevelLongFields(recent, CODEX_TOKENS), false, true)
        }
        (MetadataJsonParser.topLevelLongFields(payload, setOf("model_context_window"))["model_context_window"]
            ?: MetadataJsonParser.topLevelLongFields(info, setOf("model_context_window"))["model_context_window"])
            ?.takeIf { it >= 0 }?.let { values["contextWindow"] = it }
        recent?.let { MetadataJsonParser.topLevelLongFields(it, setOf("input_tokens"))["input_tokens"] }
            ?.takeIf { it >= 0 }?.let { values["contextTokens"] = it }
    }

    fun addTokens(tokens: Map<String, Long>, replace: Boolean, inputIncludesCache: Boolean) {
        val mappings = linkedMapOf(
            "input_tokens" to "inputTokens", "output_tokens" to "outputTokens",
            "cache_read_input_tokens" to "cachedTokens", "cached_input_tokens" to "cachedTokens",
            "cache_creation_input_tokens" to "cacheWriteTokens", "reasoning_output_tokens" to "reasoningTokens",
        )
        mappings.forEach { (source, target) ->
            tokens[source]?.takeIf { it >= 0 }?.let { if (replace) values[target] = it else increment(target, it) }
        }
        if (tokens.isNotEmpty()) {
            val total = tokens["total_tokens"] ?: ((tokens["input_tokens"] ?: 0) + (tokens["output_tokens"] ?: 0) +
                if (inputIncludesCache) 0 else (tokens["cache_read_input_tokens"] ?: 0) + (tokens["cache_creation_input_tokens"] ?: 0))
            if (replace) values["totalTokens"] = total.coerceAtLeast(0) else increment("totalTokens", total)
        }
    }

    fun toolResult(line: String) {
        val content = MetadataJsonParser.rawPath(line, "message", "content") ?: return
        MetadataJsonParser.arrayElements(content).orEmpty().forEach { block ->
            if (MetadataJsonParser.topLevelStringFields(block, setOf("type"))["type"] == "tool_result" &&
                MetadataJsonParser.topLevelBooleanFields(block, setOf("is_error"))["is_error"] == true) increment("toolErrors", 1)
        }
    }

    fun tool(name: String?, id: String?) {
        if (id != null && !tools.add(id)) return
        increment("toolCalls", 1)
        val normalized = name?.lowercase()?.substringAfterLast("__")
        if (normalized in EDIT_TOOLS) edited = true
        if (normalized in setOf("agent", "task", "spawn_agent", "spawn_subagent")) increment("subagentCalls", 1)
    }

    private fun finishTurn() {
        if (hasPrompt && edited) increment("editTurns", 1)
        edited = false
    }

    fun increment(key: String, count: Long) {
        if (count >= 0) values[key] = (values[key] ?: 0) + count
    }

    val sessionTitle: String? get() = additional.title

    fun metric(key: String, value: Long?) {
        value?.takeIf { it >= 0 }?.let { values[key] = it }
    }

    fun recordedBounds(start: Instant?, end: Instant?) {
        recordedStart = start
        recordedEnd = end
    }

    fun snapshot(): Map<String, String> {
        val result = values.toMutableMap()
        if (!additional.costComplete) result.remove("reportedCostTicks")?.let { result["partialRecordedCostTicks"] = it }
        if (agentId == "grok" && (grokCostIncomplete || grokUsageEvents != grokCostEvents)) result.remove("reportedCostTicks")
        if (hasPrompt) result["editTurns"] = (result["editTurns"] ?: 0) + if (edited) 1 else 0
        val start = listOfNotNull(first, recordedStart).minOrNull()
        val end = listOfNotNull(last, recordedEnd).maxOrNull()
        start?.let { result["firstEventMillis"] = it.toEpochMilli() }
        end?.let { result["lastEventMillis"] = it.toEpochMilli() }
        if (start != null && end != null) result["elapsedMillis"] = end.toEpochMilli() - start.toEpochMilli()
        if (first != null && last != null) result.putIfAbsent("activeMillis", 0)
        return SessionStatistics.sanitize(result.mapValues { it.value.toString() } +
            if (models.isEmpty()) emptyMap() else mapOf("models" to models.joinToString(", ")))
    }

    private companion object {
        val CLAUDE_TOKENS = setOf("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")
        val CODEX_TOKENS = setOf("input_tokens", "output_tokens", "cached_input_tokens", "reasoning_output_tokens", "total_tokens")
        val GROK_TOKENS = CLAUDE_TOKENS + CODEX_TOKENS + setOf("inputTokens", "outputTokens", "totalTokens", "cachedReadTokens", "cacheReadInputTokens", "cacheCreationTokens", "cachedWriteTokens", "cacheWriteInputTokens", "reasoningTokens", "modelCalls", "model_calls", "apiDurationMs", "api_duration_ms", "costUsdTicks", "totalCostUsdTicks", "cost_usd_ticks", "total_cost_usd_ticks")
        val EDIT_TOOLS = setOf("apply_patch", "edit", "write", "multiedit", "notebookedit", "search_replace", "str_replace", "create_file", "write_file", "write_to_file", "replace_file_content", "multi_replace_file_content", "fs_write")
    }
}
