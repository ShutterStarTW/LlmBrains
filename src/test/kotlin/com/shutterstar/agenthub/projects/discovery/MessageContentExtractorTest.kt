package com.shutterstar.agenthub.projects.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageContentExtractorTest {
    @Test
    fun `a plain string content value is decoded`() {
        val rawLiteral = "\"line one\\nline \\\"two\\\"\""

        assertEquals("line one\nline \"two\"", MessageContentExtractor.text(rawLiteral))
    }

    @Test
    fun `the first text-bearing block of a content array is used`() {
        val content = """[{"type":"tool_use","id":"1"},{"type":"text","text":"actual reply"}]"""

        assertEquals("actual reply", MessageContentExtractor.text(content))
    }

    @Test
    fun `custom block text fields cover Kiro style blocks`() {
        val content = """[{"kind":"text","data":"kiro prompt"}]"""

        assertEquals("kiro prompt", MessageContentExtractor.text(content, setOf("data")))
    }

    @Test
    fun `unrecognized or empty content yields null`() {
        assertNull(MessageContentExtractor.text(null))
        assertNull(MessageContentExtractor.text("[]"))
        assertNull(MessageContentExtractor.text("""{"type":"text"}"""))
        assertNull(MessageContentExtractor.text("42"))
    }

    @Test
    fun `tool result blocks are detected`() {
        assertTrue(MessageContentExtractor.hasBlockOfType("""[{"type":"tool_result","content":"x"}]""", setOf("tool_result")))
        assertFalse(MessageContentExtractor.hasBlockOfType("""[{"type":"text","text":"x"}]""", setOf("tool_result")))
        assertFalse(MessageContentExtractor.hasBlockOfType(""""plain"""", setOf("tool_result")))
    }

    @Test
    fun `titles are one line, truncated, and never agent-injected tag blocks`() {
        assertEquals("hello world", MessageContentExtractor.titleText("  hello\n  world "))
        assertNull(MessageContentExtractor.titleText("<command-name>/model</command-name>"))
        assertNull(MessageContentExtractor.titleText("   "))
        val long = MessageContentExtractor.titleText("x".repeat(400), maxLength = 300)!!
        assertEquals(301, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `wrapped user text is unwrapped, with or without attributes`() {
        assertEquals("fix it", MessageContentExtractor.titleText("<USER_REQUEST>\nfix it\n</USER_REQUEST>\n<ADDITIONAL_METADATA>x</ADDITIONAL_METADATA>"))
        assertEquals("do the task", MessageContentExtractor.titleText("<user_input mode=\"act\">do the task</user_input>"))
        assertEquals("unclosed", MessageContentExtractor.titleText("<user_query>unclosed"))
    }

    @Test
    fun `injected context is told apart from wrapped user text`() {
        assertTrue(MessageContentExtractor.isInjectedContext("<user_info>OS: Windows</user_info>"))
        assertFalse(MessageContentExtractor.isInjectedContext("<user_query>hi</user_query>"))
        assertFalse(MessageContentExtractor.isInjectedContext("plain prompt"))
        assertFalse(MessageContentExtractor.isInjectedContext(null))
    }

    @Test
    fun `json path helpers walk nested objects and arrays`() {
        val line = """{"payload":{"type":"item_completed","item":{"type":"UserMessage","content":[1,"two",{"a":3}]}}}"""

        assertEquals("UserMessage", MetadataJsonParser.stringAtPath(line, "payload", "item", "type"))
        assertEquals(listOf("1", "\"two\"", """{"a":3}"""), MetadataJsonParser.arrayElements(MetadataJsonParser.rawPath(line, "payload", "item", "content")!!))
        assertNull(MetadataJsonParser.rawPath(line, "payload", "missing"))
        assertNull(MetadataJsonParser.arrayElements("[1,2"))
    }
}
