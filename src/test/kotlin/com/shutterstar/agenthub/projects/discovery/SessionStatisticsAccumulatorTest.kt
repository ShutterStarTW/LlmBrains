package com.shutterstar.agenthub.projects.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SessionStatisticsAccumulatorTest {
    @Test
    fun `should count Claude response usage once while retaining streamed tools`() {
        val stats = SessionStatisticsAccumulator("claude")
        stats.userPrompt()
        val record = """{"type":"assistant","requestId":"r","timestamp":"2026-10-02T10:00:00Z","message":{"id":"m","model":"claude-test","usage":{"input_tokens":10,"cache_read_input_tokens":20,"cache_creation_input_tokens":30,"output_tokens":40,"output_tokens_details":{"thinking_tokens":12}},"content":[{"type":"tool_use","id":"t","name":"Edit","input":{"api_key":"secret"}}]}}"""
        stats.record(record)
        stats.record(record)
        stats.record("""{"type":"assistant","requestId":"r","message":{"id":"m","content":[{"type":"tool_use","id":"t2","name":"Task"}]}}""")
        stats.toolResult("""{"message":{"content":[{"type":"tool_result","is_error":true}]}}""")
        stats.userPrompt()
        stats.record("""{"type":"assistant","message":{"id":"m2","model":"claude-second","usage":{"input_tokens":1,"output_tokens":2}}}""")
        val result = stats.snapshot()
        assertEquals("103", result["totalTokens"])
        assertEquals("11", result["inputTokens"])
        assertEquals("12", result["reasoningTokens"])
        assertEquals("2", result["modelCalls"])
        assertEquals("2", result["toolCalls"])
        assertEquals("1", result["editTurns"])
        assertEquals("1", result["subagentCalls"])
        assertEquals("1", result["toolErrors"])
        assertEquals("claude-test, claude-second", result["models"])
        assertFalse(result.toString().contains("secret"))
        assertEquals(result, stats.snapshot())
    }

    @Test
    fun `should replace cumulative Codex counters without adding cached or reasoning tokens again`() {
        val stats = SessionStatisticsAccumulator("codex")
        stats.record("""{"type":"turn_context","payload":{"model":"gpt-test"}}""")
        fun usage(input: Int, output: Int) = """{"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":$input,"cached_input_tokens":20,"output_tokens":$output,"reasoning_output_tokens":5},"last_token_usage":{"input_tokens":45,"total_tokens":50},"model_context_window":200000}}}"""
        stats.record(usage(100, 10))
        stats.record(usage(100, 10))
        stats.record(usage(200, 30))
        val result = stats.snapshot()
        assertEquals("230", result["totalTokens"])
        assertEquals("200", result["inputTokens"])
        assertEquals("20", result["cachedTokens"])
        assertEquals("5", result["reasoningTokens"])
        assertEquals("200000", result["contextWindow"])
        assertEquals("45", result["contextTokens"])
        assertFalse("modelCalls" in result)
    }

    @Test
    fun `should exclude idle gaps and out of order records from active duration`() {
        val stats = SessionStatisticsAccumulator("claude")
        listOf("10:00:00", "10:01:00", "10:00:30", "10:02:00", "11:02:00", "11:03:00").forEach {
            stats.record("""{"timestamp":"2026-10-02T${it}Z"}""")
        }
        assertEquals("180000", stats.snapshot()["activeMillis"])
        assertEquals("3780000", stats.snapshot()["elapsedMillis"])
    }

    @Test
    fun `should keep unavailable usage distinct from measured zero`() {
        val stats = SessionStatisticsAccumulator("claude")
        stats.record("not json")
        stats.record("""{"type":"assistant","message":{"model":"<synthetic>"}}""")
        assertFalse("totalTokens" in stats.snapshot())
        assertFalse("models" in stats.snapshot())
        stats.record("""{"type":"assistant","message":{"usage":{"input_tokens":0,"output_tokens":0}}}""")
        assertEquals("0", stats.snapshot()["totalTokens"])
    }

    @Test
    fun `should normalize Grok provider usage and only show completely reported cost`() {
        val stats = SessionStatisticsAccumulator("grok")
        stats.record("""{"timestamp":1790935200,"params":{"update":{"sessionUpdate":"user_message_chunk","content":{"text":"hello"}}}}""")
        stats.record("""{"timestamp":1790935201,"params":{"update":{"sessionUpdate":"tool_call","toolCallId":"t","_meta":{"x.ai/tool":{"name":"write_file"}}}}}""")
        stats.record("""{"timestamp":1790935202,"params":{"update":{"sessionUpdate":"turn_completed","usage":{"inputTokens":100,"cachedReadTokens":20,"cacheCreationTokens":10,"outputTokens":30,"reasoningTokens":5,"modelCalls":2,"apiDurationMs":1000,"costUsd":0.123,"modelUsage":{"grok-test":{"totalTokens":130}}}}}}""")
        val result = stats.snapshot()
        assertEquals("130", result["totalTokens"])
        assertEquals("70", result["inputTokens"])
        assertEquals("5", result["reasoningTokens"])
        assertEquals("1", result["editTurns"])
        assertEquals("1230000000", result["reportedCostTicks"])
        assertEquals("grok-test", result["models"])
        stats.record("""{"params":{"update":{"sessionUpdate":"turn_completed","usage":{"inputTokens":1,"outputTokens":2}}}}""")
        assertFalse("reportedCostTicks" in stats.snapshot())
    }
}
