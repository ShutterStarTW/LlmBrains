package com.shutterstar.agenthub.projects.discovery

/**
 * Best-effort plain text of a chat message's raw `content` value, shared by every provider:
 * either a JSON string or an array of content blocks (`[{"type":"text","text":"..."}]`, or Kiro's
 * `[{"kind":"text","data":"..."}]`). An unrecognized shape yields null rather than a fragment.
 */
internal object MessageContentExtractor {
    private val WHITESPACE = Regex("\\s+")
    private const val DEFAULT_MAX_LENGTH = 300
    private val DEFAULT_TEXT_FIELDS = setOf("text")
    private const val USER_WRAPPER_TAGS = "USER_REQUEST|user_request|user_input|user_query"
    private val USER_WRAPPER = Regex("<($USER_WRAPPER_TAGS)(?:\\s[^>]*)?>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
    private val USER_WRAPPER_OPEN = Regex("<($USER_WRAPPER_TAGS)(?:\\s[^>]*)?>")

    fun text(rawContent: String?, textFields: Set<String> = DEFAULT_TEXT_FIELDS): String? {
        val raw = rawContent?.trim() ?: return null
        return when {
            raw.startsWith("\"") -> decodeString(raw)
            raw.startsWith("[") -> MetadataJsonParser.arrayElements(raw)
                ?.firstNotNullOfOrNull { blockText(it, textFields) }
            else -> null
        }
    }

    /** Whether a content block array contains a block whose `type` is one of [types] (e.g. a tool result). */
    fun hasBlockOfType(rawContent: String?, types: Set<String>): Boolean {
        val raw = rawContent?.trim()?.takeIf { it.startsWith("[") } ?: return false
        return MetadataJsonParser.arrayElements(raw).orEmpty().any { element ->
            MetadataJsonParser.topLevelStringFields(element, setOf("type"))["type"] in types
        }
    }

    /**
     * Several agents wrap what the user typed in a marker tag (Antigravity `<USER_REQUEST>`, Cline
     * `<user_input>`, Grok `<user_query>`), usually next to injected context blocks; this returns
     * the wrapped text, or [text] unchanged when there is no such wrapper.
     */
    fun unwrapUserText(text: String): String =
        USER_WRAPPER.find(text)?.groupValues?.get(2)?.trim()
            ?: USER_WRAPPER_OPEN.find(text)?.let { text.substring(it.range.last + 1).trim() }
            ?: text

    /** Whether [text] is agent-injected context (a tag block) rather than something the user typed. */
    fun isInjectedContext(text: String?): Boolean {
        val trimmed = text?.trimStart() ?: return false
        return trimmed.startsWith("<") && USER_WRAPPER_OPEN.find(trimmed) == null
    }

    /**
     * [text] as a one-line title: unwrapped, whitespace collapsed, truncated to [maxLength] with an
     * ellipsis. Tag-wrapped text (`<command-name>…`, `<environment_context>…`) is injected by the
     * agent, not typed by the user, so it never becomes a title.
     */
    fun titleText(text: String?, maxLength: Int = DEFAULT_MAX_LENGTH): String? {
        val collapsed = text?.let(::unwrapUserText)?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (collapsed.startsWith("<")) return null
        return if (collapsed.length > maxLength) collapsed.take(maxLength).trimEnd() + "…" else collapsed
    }

    /** Decodes a raw JSON string literal (quotes included) of any length. */
    fun decodeString(rawLiteral: String): String? {
        val raw = rawLiteral.trim()
        if (raw.length < 2 || !raw.startsWith("\"") || !raw.endsWith("\"")) return null
        return unescape(raw.substring(1, raw.length - 1))
    }

    private fun blockText(element: String, textFields: Set<String>): String? {
        if (element.startsWith("\"")) return decodeString(element)
        val raw = textFields.firstNotNullOfOrNull { field ->
            MetadataJsonParser.rawTopLevelField(element, field)?.trim()?.takeIf { it.startsWith("\"") }
        } ?: return null
        return decodeString(raw)?.takeIf { it.isNotBlank() }
    }

    private fun unescape(value: String): String {
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    'n' -> builder.append('\n')
                    'r' -> builder.append('\r')
                    't' -> builder.append('\t')
                    'b' -> builder.append('\b')
                    'f' -> builder.append('\u000C')
                    'u' -> {
                        val codePoint = value.substring(index + 2, minOf(index + 6, value.length))
                            .takeIf { it.length == 4 }
                            ?.toIntOrNull(16)
                        if (codePoint != null) {
                            builder.append(codePoint.toChar())
                            index += 6
                            continue
                        }
                        builder.append('u')
                    }
                    else -> builder.append(value[index + 1])
                }
                index += 2
            } else {
                builder.append(character)
                index++
            }
        }
        return builder.toString()
    }
}
