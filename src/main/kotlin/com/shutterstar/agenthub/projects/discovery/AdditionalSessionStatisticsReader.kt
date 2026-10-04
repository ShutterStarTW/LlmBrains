package com.shutterstar.agenthub.projects.discovery

/** Provider-specific native formats. Shared accumulation never retains content or arguments. */
internal class AdditionalSessionStatisticsReader(
    private val stats: SessionStatisticsAccumulator,
    private val agentId: String,
) {
    private val seen = hashSetOf<String>()
    private val failedTools = hashSetOf<String>()
    private var usageRecords = 0
    private var costRecords = 0
    var title: String? = null
        private set
    private var manualTitle = false
    val costComplete: Boolean get() = usageRecords == costRecords

    fun record(json: String) {
        when (agentId) {
            "qwen" -> qwen(json)
            "copilot" -> copilot(json)
            "cline" -> cline(json)
            "kiro" -> kiro(json)
            "antigravity" -> antigravity(json)
            "opencode" -> openCode(json)
        }
    }

    private fun unique(id: String?): Boolean = id == null || seen.add(id)
    private fun number(json: String, key: String): Long? = MetadataJsonParser.topLevelLongFields(json, setOf(key))[key]?.takeIf { it >= 0 }
    private fun string(json: String, key: String): String? = MetadataJsonParser.topLevelStringFields(json, setOf(key))[key]
    private fun raw(json: String, key: String): String? = MetadataJsonParser.rawTopLevelField(json, key)
    private fun path(json: String, vararg keys: String): String? = MetadataJsonParser.rawPath(json, *keys)
    private fun elements(json: String, key: String): List<String> = raw(json, key)?.let(MetadataJsonParser::arrayElements).orEmpty()
    private fun time(json: String, key: String) {
        (LocalSessionSupport.parseTimestamp(string(json, key)) ?: LocalSessionSupport.epochTimestamp(number(json, key)))?.let(stats::timestamp)
    }

    private fun usage(json: String, mapping: Map<String, String>, inclusiveCache: Boolean, reasoningSeparate: Boolean = false) {
        val numbers = MetadataJsonParser.topLevelLongFields(json, mapping.keys).filterValues { it >= 0 }
        if (numbers.isEmpty()) return
        val normalized = numbers.mapKeys { mapping.getValue(it.key) }.toMutableMap()
        if (reasoningSeparate && "output_tokens" in normalized) {
            normalized["output_tokens"] = normalized.getValue("output_tokens") + (normalized["reasoning_output_tokens"] ?: 0)
        }
        stats.addTokens(normalized, false, inclusiveCache)
        stats.increment("modelCalls", 1)
        normalized["input_tokens"]?.let { stats.metric("contextTokens", it + if (inclusiveCache) 0 else (normalized["cache_read_input_tokens"] ?: 0) + (normalized["cache_creation_input_tokens"] ?: 0)) }
        usageRecords++
    }

    private fun cost(json: String, key: String) {
        raw(json, key)?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }?.let {
            it.movePointRight(10).setScale(0, java.math.RoundingMode.HALF_UP).runCatching { longValueExact() }.getOrNull()
        }?.let { stats.increment("reportedCostTicks", it); costRecords++ }
    }

    private fun failed(id: String?) {
        if (id == null || failedTools.add(id)) stats.increment("toolErrors", 1)
    }

    private fun qwen(json: String) {
        if (!unique(string(json, "uuid"))) return
        if (string(json, "type") == "system" && string(json, "subtype") == "custom_title") {
            raw(json, "systemPayload")?.let { payload ->
                val candidate = string(payload, "customTitle")?.takeIf { it.isNotBlank() }
                val manual = string(payload, "titleSource") != "auto"
                if (candidate != null && (manual || !manualTitle)) { title = candidate; manualTitle = manual }
            }
        }
        if (string(json, "type") == "assistant") {
            stats.model(string(json, "model"))
            raw(json, "usageMetadata")?.let { usage(it, QWEN_USAGE, true) }
            stats.metric("contextWindow", number(json, "contextWindowSize"))
            path(json, "message", "parts")?.let(MetadataJsonParser::arrayElements).orEmpty().forEach { part ->
                raw(part, "functionCall")?.let { stats.tool(string(it, "name"), string(it, "id")) }
            }
        }
        if (string(json, "type") == "tool_result") raw(json, "toolCallResult")?.let {
            if (string(it, "status") in setOf("error", "failed")) failed(string(it, "callId"))
        }
    }

    private fun cline(json: String) {
        if (!unique(string(json, "id"))) return
        time(json, "ts")
        if (string(json, "role") == "assistant") {
            stats.model(MetadataJsonParser.stringAtPath(json, "modelInfo", "id"))
            raw(json, "metrics")?.let { usage(it, CAMEL_USAGE, true); cost(it, "cost") }
        }
        elements(json, "content").forEach { block ->
            when (string(block, "type")) {
                "tool_use" -> stats.tool(string(block, "name"), string(block, "id"))
                "tool_result" -> if (MetadataJsonParser.topLevelBooleanFields(block, setOf("is_error"))["is_error"] == true) failed(string(block, "tool_use_id"))
            }
        }
    }

    private fun kiro(json: String) {
        val data = raw(json, "data") ?: return
        if (!unique(string(data, "message_id"))) return
        path(data, "meta")?.let { time(it, "timestamp") }
        elements(data, "content").forEach { block ->
            val value = raw(block, "data") ?: return@forEach
            when (string(block, "kind")) {
                "thinking" -> stats.model(string(value, "modelId"))
                "toolUse" -> stats.tool(string(value, "name"), string(value, "toolUseId"))
                "toolResult" -> if (string(value, "status") in setOf("error", "failed")) failed(string(value, "toolUseId"))
            }
        }
    }

    private fun antigravity(json: String) {
        time(json, "created_at")
        // Transcript tool_calls have no stable IDs; a step index identifies the containing step.
        val index = number(json, "step_index")
        if (index != null && !unique(index.toString())) return
        elements(json, "tool_calls").forEachIndexed { position, tool ->
            stats.tool(string(tool, "name"), index?.let { "$it:$position" })
        }
    }

    private fun copilot(json: String) {
        if (!unique(string(json, "id"))) return
        val data = raw(json, "data") ?: return
        val type = string(json, "type")
        stats.model(string(data, "model") ?: string(data, "newModel") ?: string(data, "chosenModel"))
        when (type) {
            "model.model_call_success" -> {
                stats.model(MetadataJsonParser.stringAtPath(data, "modelCall", "model"))
                (raw(data, "responseUsage") ?: path(data, "responseChunk", "usage"))?.let { u ->
                    val normalized = MetadataJsonParser.topLevelLongFields(u, OPENAI_USAGE.keys).mapKeys { OPENAI_USAGE.getValue(it.key) }.toMutableMap()
                    path(u, "prompt_tokens_details")?.let { number(it, "cached_tokens") }?.let { normalized["cache_read_input_tokens"] = it }
                    path(u, "completion_tokens_details")?.let { number(it, "reasoning_tokens") }?.let { normalized["reasoning_output_tokens"] = it }
                    normalized["input_tokens"]?.let { normalized["input_tokens"] = (it - (normalized["cache_read_input_tokens"] ?: 0)).coerceAtLeast(0) }
                    if (normalized.isNotEmpty()) { stats.addTokens(normalized, false, false); stats.increment("modelCalls", 1) }
                }
                number(data, "modelCallDurationMs")?.let { stats.increment("apiMillis", it) }
            }
            "tool.execution_start" -> stats.tool(string(data, "toolName"), string(data, "toolCallId"))
            "tool.execution_complete" -> if (MetadataJsonParser.topLevelBooleanFields(data, setOf("success"))["success"] == false) failed(string(data, "toolCallId"))
            "model.model_call_failure" -> stats.increment("modelErrors", 1)
            "session.shutdown" -> {
                val details = raw(data, "tokenDetails")
                if (details != null) {
                    val totals = linkedMapOf<String, Long>()
                    mapOf("input" to "input_tokens", "cache_read" to "cache_read_input_tokens", "cache_write" to "cache_creation_input_tokens", "output" to "output_tokens").forEach { (field, target) ->
                        path(details, field)?.let { number(it, "tokenCount") }?.let { totals[target] = it }
                    }
                    // Shutdown reports mutually exclusive token categories, unlike per-call prompt_tokens.
                    if (totals.isNotEmpty()) stats.addTokens(totals, true, false)
                }
                stats.metric("apiMillis", number(data, "totalApiDurationMs"))
                stats.metric("nanoAiu", number(data, "totalNanoAiu"))
                stats.metric("premiumRequests", number(data, "totalPremiumRequests"))
                stats.metric("contextTokens", number(data, "currentTokens"))
                path(data, "codeChanges")?.let { changes ->
                    stats.metric("linesAdded", number(changes, "linesAdded"))
                    stats.metric("linesRemoved", number(changes, "linesRemoved"))
                    if (raw(changes, "filesModified") != null) stats.metric("filesModified", elements(changes, "filesModified").size.toLong())
                }
                raw(data, "modelMetrics")?.let(MetadataJsonParser::objectEntries).orEmpty().forEach { (model, _) -> stats.model(model) }
            }
        }
    }

    private fun openCode(json: String) {
        if (!unique(string(json, "id"))) return
        path(json, "time")?.let { time(it, "created") }
        if (string(json, "type") == "tool") {
            val id = string(json, "callID") ?: string(json, "id")
            stats.tool(string(json, "tool"), id)
            if (MetadataJsonParser.stringAtPath(json, "state", "status") == "error") failed(id)
        }
        if (string(json, "role") == "assistant") {
            stats.model(string(json, "modelID"))
            raw(json, "tokens")?.let { tokens ->
                val normalized = MetadataJsonParser.topLevelLongFields(tokens, OPENCODE_USAGE.keys).mapKeys { OPENCODE_USAGE.getValue(it.key) }.toMutableMap()
                path(tokens, "cache")?.let { cache ->
                    number(cache, "read")?.let { normalized["cache_read_input_tokens"] = it }
                    number(cache, "write")?.let { normalized["cache_creation_input_tokens"] = it }
                }
                normalized["output_tokens"]?.let { normalized["output_tokens"] = it + (normalized["reasoning_output_tokens"] ?: 0) }
                if (normalized.isNotEmpty()) {
                    stats.addTokens(normalized, false, false)
                    stats.increment("modelCalls", 1)
                    usageRecords++
                    cost(json, "cost")
                }
            }
        }
    }

    private companion object {
        val CAMEL_USAGE = mapOf("inputTokens" to "input_tokens", "outputTokens" to "output_tokens", "cacheReadTokens" to "cache_read_input_tokens", "cacheWriteTokens" to "cache_creation_input_tokens", "reasoningTokens" to "reasoning_output_tokens")
        val QWEN_USAGE = mapOf("promptTokenCount" to "input_tokens", "candidatesTokenCount" to "output_tokens", "cachedContentTokenCount" to "cache_read_input_tokens", "thoughtsTokenCount" to "reasoning_output_tokens", "totalTokenCount" to "total_tokens")
        val OPENAI_USAGE = mapOf("prompt_tokens" to "input_tokens", "completion_tokens" to "output_tokens", "total_tokens" to "total_tokens")
        val OPENCODE_USAGE = mapOf("input" to "input_tokens", "output" to "output_tokens", "reasoning" to "reasoning_output_tokens", "total" to "total_tokens")
    }
}
