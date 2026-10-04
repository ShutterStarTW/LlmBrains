package com.shutterstar.agenthub.projects.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdditionalSessionStatisticsTest {
    @Test
    fun `should read Qwen usage without counting cached and thinking tokens twice`() {
        val stats = SessionStatisticsAccumulator("qwen")
        stats.userPrompt("change file")
        val record = """{"type":"assistant","uuid":"a","model":"qwen-test","contextWindowSize":200000,"usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":50,"candidatesTokenCount":20,"thoughtsTokenCount":5,"totalTokenCount":120},"message":{"parts":[{"functionCall":{"id":"t","name":"edit","args":{"password":"secret"}}}]}}"""
        stats.record(record)
        stats.record(record)
        stats.record("""{"type":"system","subtype":"custom_title","systemPayload":{"customTitle":"Manual name"}}""")
        stats.record("""{"type":"system","subtype":"custom_title","systemPayload":{"customTitle":"Generated name","titleSource":"auto"}}""")
        assertEquals("120", stats.snapshot()["totalTokens"])
        assertEquals("50", stats.snapshot()["cachedTokens"])
        assertEquals("5", stats.snapshot()["reasoningTokens"])
        assertEquals("1", stats.snapshot()["modelCalls"])
        assertEquals("1", stats.snapshot()["editTurns"])
        assertEquals("200000", stats.snapshot()["contextWindow"])
        assertEquals("Manual name", stats.sessionTitle)
        assertFalse(stats.snapshot().toString().contains("secret"))
    }

    @Test
    fun `should read terminal Cline metrics and tool failures including measured zero cost`() {
        val stats = SessionStatisticsAccumulator("cline")
        stats.userPrompt("fix")
        stats.record("""{"id":"early","role":"assistant","modelInfo":{"id":"model-test"},"content":[{"type":"tool_use","id":"t","name":"Write"}]}""")
        val terminal = """{"id":"terminal","role":"assistant","ts":1790935200000,"modelInfo":{"id":"model-test"},"metrics":{"inputTokens":100,"outputTokens":20,"cacheReadTokens":60,"cacheWriteTokens":10,"cost":0}}"""
        stats.record(terminal)
        stats.record(terminal)
        stats.record("""{"id":"results","role":"user","content":[{"type":"tool_result","tool_use_id":"t","is_error":true}]}""")
        assertEquals("120", stats.snapshot()["totalTokens"])
        assertEquals("0", stats.snapshot()["reportedCostTicks"])
        assertEquals("1", stats.snapshot()["toolErrors"])
        assertEquals("1", stats.snapshot()["editTurns"])
        stats.record("""{"id":"missing-cost","role":"assistant","metrics":{"inputTokens":1,"outputTokens":2}}""")
        assertFalse("reportedCostTicks" in stats.snapshot())
        assertEquals("0", stats.snapshot()["partialRecordedCostTicks"])
    }

    @Test
    fun `should prefer Copilot cumulative shutdown categories to per-call usage`() {
        val stats = SessionStatisticsAccumulator("copilot")
        stats.userPrompt("edit")
        stats.record("""{"id":"call","type":"model.model_call_success","data":{"modelCall":{"model":"model-test"},"modelCallDurationMs":1000,"responseUsage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"prompt_tokens_details":{"cached_tokens":60}}}}""")
        stats.record("""{"id":"tool","type":"tool.execution_start","data":{"toolCallId":"t","toolName":"edit"}}""")
        stats.record("""{"id":"end","type":"session.shutdown","data":{"totalApiDurationMs":2000,"totalNanoAiu":123,"tokenDetails":{"input":{"tokenCount":40},"cache_read":{"tokenCount":60},"cache_write":{"tokenCount":0},"output":{"tokenCount":20}},"codeChanges":{"linesAdded":12,"linesRemoved":3,"filesModified":["a","b"]}}}""")
        val result = stats.snapshot()
        assertEquals("120", result["totalTokens"])
        assertEquals("40", result["inputTokens"])
        assertEquals("60", result["cachedTokens"])
        assertEquals("2000", result["apiMillis"])
        assertEquals("123", result["nanoAiu"])
        assertEquals("2", result["filesModified"])
        assertFalse("reportedCostTicks" in result)
        assertEquals("1", result["editTurns"])
    }

    @Test
    fun `should extract Kiro routed model and tools without inventing token usage`() {
        val stats = SessionStatisticsAccumulator("kiro")
        stats.userPrompt("fix")
        stats.record("""{"kind":"AssistantMessage","data":{"message_id":"m","content":[{"kind":"thinking","data":{"modelId":"claude-test"}},{"kind":"toolUse","data":{"toolUseId":"t","name":"write_file"}}]}}""")
        stats.record("""{"kind":"ToolResults","data":{"message_id":"r","content":[{"kind":"toolResult","data":{"toolUseId":"t","status":"error"}}]}}""")
        assertEquals("claude-test", stats.snapshot()["models"])
        assertEquals("1", stats.snapshot()["toolCalls"])
        assertEquals("1", stats.snapshot()["toolErrors"])
        assertFalse("totalTokens" in stats.snapshot())
    }

    @Test
    fun `should read OpenCode exclusive tokens including separate reasoning and recorded cost`() {
        val stats = SessionStatisticsAccumulator("opencode")
        stats.userPrompt()
        stats.record("""{"id":"m","role":"assistant","modelID":"gpt-test","tokens":{"input":100,"output":20,"reasoning":5,"cache":{"read":60,"write":10},"total":195},"cost":0.00123}""")
        stats.record("""{"id":"p","type":"tool","tool":"apply_patch","callID":"t","state":{"status":"error"}}""")
        assertEquals("195", stats.snapshot()["totalTokens"])
        assertEquals("25", stats.snapshot()["outputTokens"])
        assertEquals("12300000", stats.snapshot()["reportedCostTicks"])
        assertEquals("1", stats.snapshot()["editTurns"])
        assertEquals("1", stats.snapshot()["toolErrors"])
    }

    @Test
    fun `should read Antigravity step times and tools while ignoring duplicate steps`() {
        val stats = SessionStatisticsAccumulator("antigravity")
        stats.userPrompt("fix")
        val record = """{"step_index":1,"type":"PLANNER_RESPONSE","created_at":"2026-10-02T10:00:00Z","tool_calls":[{"name":"write_file","args":{"token":"private"}}]}"""
        stats.record(record)
        stats.record(record)
        stats.record("""{"step_index":2,"type":"GENERIC","created_at":"2026-10-02T10:00:10Z"}""")
        assertEquals("1", stats.snapshot()["toolCalls"])
        assertEquals("10000", stats.snapshot()["activeMillis"])
        assertFalse("totalTokens" in stats.snapshot())
        assertTrue(stats.snapshot().values.none { "private" in it })
    }
}
