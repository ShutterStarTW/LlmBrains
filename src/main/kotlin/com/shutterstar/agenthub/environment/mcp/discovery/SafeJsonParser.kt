package com.shutterstar.agenthub.environment.mcp.discovery

internal sealed interface JsonValue

internal data class JsonObject(
    val fields: Map<String, JsonValue>,
) : JsonValue

internal data class JsonArray(
    val values: List<JsonValue>,
) : JsonValue

internal data class JsonString(
    val value: String,
) : JsonValue

internal data object JsonScalar : JsonValue

internal object SafeJsonParser {
    fun parse(content: String): JsonValue? = runCatching {
        Parser(content).parse()
    }.getOrNull()

    fun parseJsonc(content: String): JsonValue? = runCatching {
        Parser(JsoncSanitizer.sanitize(content)).parse()
    }.getOrNull()

    private class Parser(
        private val content: String,
    ) {
        private var index = 0
        private var nodeCount = 0

        fun parse(): JsonValue {
            val value = parseValue(depth = 0)
            skipWhitespace()
            require(index == content.length)
            return value
        }

        private fun parseValue(depth: Int): JsonValue {
            require(depth <= MAXIMUM_DEPTH)
            require(++nodeCount <= MAXIMUM_NODES)
            skipWhitespace()
            require(index < content.length)
            return when (content[index]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> JsonString(parseString())
                't' -> parseLiteral("true")
                'f' -> parseLiteral("false")
                'n' -> parseLiteral("null")
                '-', in '0'..'9' -> parseNumber()
                else -> error("Unexpected JSON token")
            }
        }

        private fun parseObject(depth: Int): JsonObject {
            index++
            skipWhitespace()
            val fields = linkedMapOf<String, JsonValue>()
            if (consume('}')) {
                return JsonObject(fields)
            }
            while (true) {
                skipWhitespace()
                require(index < content.length && content[index] == '"')
                val key = parseString()
                skipWhitespace()
                require(consume(':'))
                fields[key] = parseValue(depth)
                skipWhitespace()
                if (consume('}')) {
                    return JsonObject(fields)
                }
                require(consume(','))
            }
        }

        private fun parseArray(depth: Int): JsonArray {
            index++
            skipWhitespace()
            val values = mutableListOf<JsonValue>()
            if (consume(']')) {
                return JsonArray(values)
            }
            while (true) {
                values += parseValue(depth)
                skipWhitespace()
                if (consume(']')) {
                    return JsonArray(values)
                }
                require(consume(','))
            }
        }

        private fun parseString(): String {
            require(consume('"'))
            val result = StringBuilder()
            while (index < content.length) {
                val character = content[index++]
                when (character) {
                    '"' -> return result.toString()
                    '\\' -> result.append(parseEscape())
                    else -> {
                        require(character.code >= 0x20)
                        require(result.length < MAXIMUM_STRING_CHARACTERS)
                        result.append(character)
                    }
                }
            }
            error("Unterminated JSON string")
        }

        private fun parseEscape(): Char {
            require(index < content.length)
            return when (val escaped = content[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    require(index + 4 <= content.length)
                    val character = content.substring(index, index + 4).toInt(16).toChar()
                    index += 4
                    character
                }
                else -> error("Invalid JSON escape")
            }
        }

        private fun parseLiteral(literal: String): JsonValue {
            require(content.startsWith(literal, index))
            index += literal.length
            return JsonScalar
        }

        private fun parseNumber(): JsonValue {
            val start = index
            if (content[index] == '-') index++
            require(readDigits())
            if (consume('.')) require(readDigits())
            if (index < content.length && content[index].lowercaseChar() == 'e') {
                index++
                if (index < content.length && (content[index] == '+' || content[index] == '-')) index++
                require(readDigits())
            }
            require(index > start)
            return JsonScalar
        }

        private fun readDigits(): Boolean {
            val start = index
            while (index < content.length && content[index].isDigit()) index++
            return index > start
        }

        private fun consume(expected: Char): Boolean {
            if (index >= content.length || content[index] != expected) return false
            index++
            return true
        }

        private fun skipWhitespace() {
            while (index < content.length && content[index].isWhitespace()) index++
        }
    }

    private const val MAXIMUM_DEPTH = 64
    private const val MAXIMUM_NODES = 100_000
    private const val MAXIMUM_STRING_CHARACTERS = 512 * 1024
}

private object JsoncSanitizer {
    fun sanitize(content: String): String = removeTrailingCommas(removeComments(content))

    private fun removeComments(content: String): String {
        val result = StringBuilder(content.length)
        var index = 0
        var inString = false
        var escaped = false
        while (index < content.length) {
            val character = content[index]
            if (inString) {
                result.append(character)
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                index++
                continue
            }

            when {
                character == '"' -> {
                    inString = true
                    result.append(character)
                    index++
                }
                character == '/' && content.getOrNull(index + 1) == '/' -> {
                    index += 2
                    while (index < content.length && content[index] != '\n') index++
                }
                character == '/' && content.getOrNull(index + 1) == '*' -> {
                    index += 2
                    var closed = false
                    while (index < content.length) {
                        if (content[index] == '\n') result.append('\n')
                        if (content[index] == '*' && content.getOrNull(index + 1) == '/') {
                            index += 2
                            closed = true
                            break
                        }
                        index++
                    }
                    require(closed)
                }
                else -> {
                    result.append(character)
                    index++
                }
            }
        }
        require(!inString)
        return result.toString()
    }

    private fun removeTrailingCommas(content: String): String {
        val result = StringBuilder(content.length)
        var index = 0
        var inString = false
        var escaped = false
        while (index < content.length) {
            val character = content[index]
            if (inString) {
                result.append(character)
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                index++
                continue
            }

            if (character == '"') {
                inString = true
                result.append(character)
                index++
                continue
            }
            if (character == ',') {
                var lookahead = index + 1
                while (lookahead < content.length && content[lookahead].isWhitespace()) lookahead++
                if (content.getOrNull(lookahead) == '}' || content.getOrNull(lookahead) == ']') {
                    index++
                    continue
                }
            }
            result.append(character)
            index++
        }
        return result.toString()
    }
}
