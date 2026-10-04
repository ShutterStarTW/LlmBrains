package com.shutterstar.agenthub.projects.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MetadataJsonParserTest {
    @Test
    fun `object entries keep each member's key and raw text, including nested braces and quoted braces`() {
        val entries = MetadataJsonParser.objectEntries(
            """{ "a": {"x": "}"}, "b" : [1, {"y": 2}], "c": "text" , "d": 7 }""",
        )

        assertEquals(
            listOf("a" to """{"x": "}"}""", "b" to """[1, {"y": 2}]""", "c" to "\"text\"", "d" to "7"),
            entries,
        )
    }

    @Test
    fun `object entries of an empty object are empty and of malformed or non-object text are null`() {
        assertEquals(emptyList<Pair<String, String>>(), MetadataJsonParser.objectEntries(" { } "))
        assertNull(MetadataJsonParser.objectEntries("""{"a": 1"""))
        assertNull(MetadataJsonParser.objectEntries("""{"a" 1}"""))
        assertNull(MetadataJsonParser.objectEntries("[1, 2]"))
        assertNull(MetadataJsonParser.objectEntries("text"))
    }

    @Test
    fun `array string elements unescape strings and skip other element types`() {
        assertEquals(
            listOf("file:///a b", "say \"hi\""),
            MetadataJsonParser.arrayStringElements("""["file:///a b", 3, {"k": "v"}, "say \"hi\"", null]"""),
        )
        assertEquals(emptyList<String>(), MetadataJsonParser.arrayStringElements("[]"))
        assertNull(MetadataJsonParser.arrayStringElements("""["unterminated""""))
        assertNull(MetadataJsonParser.arrayStringElements("""{"a": 1}"""))
    }
}
