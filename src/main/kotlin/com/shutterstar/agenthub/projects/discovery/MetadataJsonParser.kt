package com.shutterstar.agenthub.projects.discovery

object MetadataJsonParser {
    private const val MAX_KEY_CHARACTERS = 256
    private const val MAX_VALUE_CHARACTERS = 32 * 1024

    fun topLevelStringFields(json: String, requestedFields: Set<String>): Map<String, String> {
        var index = skipWhitespace(json, 0)
        if (index >= json.length || json[index] != '{') return emptyMap()
        index++
        val result = mutableMapOf<String, String>()
        while (index < json.length) {
            index = skipWhitespace(json, index)
            if (index < json.length && json[index] == '}') return result
            val key = parseString(json, index, MAX_KEY_CHARACTERS) ?: return emptyMap()
            index = skipWhitespace(json, key.nextIndex)
            if (index >= json.length || json[index] != ':') return emptyMap()
            index = skipWhitespace(json, index + 1)
            if (key.value in requestedFields && index < json.length && json[index] == '"') {
                val value = parseString(json, index, MAX_VALUE_CHARACTERS) ?: return emptyMap()
                value.value?.let { result[key.value.orEmpty()] = it }
                index = value.nextIndex
            } else {
                index = skipValue(json, index) ?: return emptyMap()
            }
            index = skipWhitespace(json, index)
            when {
                index >= json.length -> return emptyMap()
                json[index] == ',' -> index++
                json[index] == '}' -> return result
                else -> return emptyMap()
            }
        }
        return emptyMap()
    }

    fun objectStringFields(
        json: String,
        objectField: String,
        requestedFields: Set<String>,
    ): Map<String, String> {
        val range = topLevelValueRange(json, objectField) ?: return emptyMap()
        if (json[range.first] != '{') return emptyMap()
        return topLevelStringFields(json.substring(range), requestedFields)
    }

    fun topLevelLongFields(json: String, requestedFields: Set<String>): Map<String, Long> {
        var index = skipWhitespace(json, 0)
        if (index >= json.length || json[index] != '{') return emptyMap()
        index++
        val result = mutableMapOf<String, Long>()
        while (index < json.length) {
            index = skipWhitespace(json, index)
            if (index < json.length && json[index] == '}') return result
            val key = parseString(json, index, MAX_KEY_CHARACTERS) ?: return emptyMap()
            index = skipWhitespace(json, key.nextIndex)
            if (index >= json.length || json[index] != ':') return emptyMap()
            val valueStart = skipWhitespace(json, index + 1)
            val valueEnd = skipValue(json, valueStart) ?: return emptyMap()
            if (key.value in requestedFields) {
                json.substring(valueStart, valueEnd).trim().toLongOrNull()?.let { value ->
                    result[key.value.orEmpty()] = value
                }
            }
            index = skipWhitespace(json, valueEnd)
            when {
                index >= json.length -> return emptyMap()
                json[index] == ',' -> index++
                json[index] == '}' -> return result
                else -> return emptyMap()
            }
        }
        return emptyMap()
    }

    fun topLevelBooleanFields(json: String, requestedFields: Set<String>): Map<String, Boolean> {
        var index = skipWhitespace(json, 0)
        if (index >= json.length || json[index] != '{') return emptyMap()
        index++
        val result = mutableMapOf<String, Boolean>()
        while (index < json.length) {
            index = skipWhitespace(json, index)
            if (index < json.length && json[index] == '}') return result
            val key = parseString(json, index, MAX_KEY_CHARACTERS) ?: return emptyMap()
            index = skipWhitespace(json, key.nextIndex)
            if (index >= json.length || json[index] != ':') return emptyMap()
            val valueStart = skipWhitespace(json, index + 1)
            val valueEnd = skipValue(json, valueStart) ?: return emptyMap()
            if (key.value in requestedFields) {
                when (json.substring(valueStart, valueEnd).trim()) {
                    "true" -> result[key.value.orEmpty()] = true
                    "false" -> result[key.value.orEmpty()] = false
                }
            }
            index = skipWhitespace(json, valueEnd)
            when {
                index >= json.length -> return emptyMap()
                json[index] == ',' -> index++
                json[index] == '}' -> return result
                else -> return emptyMap()
            }
        }
        return emptyMap()
    }

    fun objectLongFields(
        json: String,
        objectField: String,
        requestedFields: Set<String>,
    ): Map<String, Long> {
        val range = topLevelValueRange(json, objectField) ?: return emptyMap()
        if (json[range.first] != '{') return emptyMap()
        return topLevelLongFields(json.substring(range), requestedFields)
    }

    /** Raw JSON text (untouched, whatever the value's type) of a top-level field, or null if absent/malformed. */
    fun rawTopLevelField(json: String, requestedField: String): String? {
        val range = topLevelValueRange(json, requestedField) ?: return null
        return json.substring(range)
    }

    /** Raw JSON text at a nested object path, e.g. `rawPath(line, "payload", "item", "content")`. */
    fun rawPath(json: String, vararg path: String): String? {
        var current = json
        for (field in path) {
            val range = topLevelValueRange(current, field) ?: return null
            current = current.substring(range)
        }
        return current
    }

    /** The string value at a nested object path; the last segment names the string field. */
    fun stringAtPath(json: String, vararg path: String): String? {
        if (path.isEmpty()) return null
        val parent = if (path.size == 1) json else rawPath(json, *path.copyOfRange(0, path.size - 1)) ?: return null
        return topLevelStringFields(parent, setOf(path.last()))[path.last()]
    }

    /** The raw JSON text of each element of a JSON array, or null if [rawArray] is not a well-formed array. */
    fun arrayElements(rawArray: String): List<String>? {
        var index = skipWhitespace(rawArray, 0)
        if (index >= rawArray.length || rawArray[index] != '[') return null
        index = skipWhitespace(rawArray, index + 1)
        val elements = mutableListOf<String>()
        if (index < rawArray.length && rawArray[index] == ']') return elements
        while (index < rawArray.length) {
            val end = skipArrayElement(rawArray, index) ?: return null
            elements += rawArray.substring(index, end).trim()
            index = skipWhitespace(rawArray, end)
            when {
                index >= rawArray.length -> return null
                rawArray[index] == ',' -> index = skipWhitespace(rawArray, index + 1)
                rawArray[index] == ']' -> return elements
                else -> return null
            }
        }
        return null
    }

    /** The string elements of a JSON array (other element types are skipped), or null if [rawArray] is not a well-formed array. */
    fun arrayStringElements(rawArray: String): List<String>? =
        arrayElements(rawArray)?.mapNotNull { element ->
            if (element.startsWith('"')) parseString(element, 0, MAX_VALUE_CHARACTERS)?.value else null
        }

    /** The key and raw JSON text of each member of a JSON object, or null if [rawObject] is not a well-formed object. */
    fun objectEntries(rawObject: String): List<Pair<String, String>>? {
        var index = skipWhitespace(rawObject, 0)
        if (index >= rawObject.length || rawObject[index] != '{') return null
        index = skipWhitespace(rawObject, index + 1)
        val entries = mutableListOf<Pair<String, String>>()
        if (index < rawObject.length && rawObject[index] == '}') return entries
        while (index < rawObject.length) {
            val key = parseString(rawObject, index, MAX_KEY_CHARACTERS) ?: return null
            index = skipWhitespace(rawObject, key.nextIndex)
            if (index >= rawObject.length || rawObject[index] != ':') return null
            val valueStart = skipWhitespace(rawObject, index + 1)
            val valueEnd = skipValue(rawObject, valueStart) ?: return null
            key.value?.let { entries += it to rawObject.substring(valueStart, valueEnd).trim() }
            index = skipWhitespace(rawObject, valueEnd)
            when {
                index >= rawObject.length -> return null
                rawObject[index] == ',' -> index = skipWhitespace(rawObject, index + 1)
                rawObject[index] == '}' -> return entries
                else -> return null
            }
        }
        return null
    }

    // skipValue stops scalars only at ',' or '}', so inside an array a trailing scalar would run past ']'.
    private fun skipArrayElement(json: String, startIndex: Int): Int? {
        if (startIndex >= json.length) return null
        val first = json[startIndex]
        if (first == '"' || first == '{' || first == '[') return skipValue(json, startIndex)
        var index = startIndex
        while (index < json.length && json[index] != ',' && json[index] != ']') index++
        return index
    }

    private fun topLevelValueRange(json: String, requestedField: String): IntRange? {
        var index = skipWhitespace(json, 0)
        if (index >= json.length || json[index] != '{') return null
        index++
        while (index < json.length) {
            index = skipWhitespace(json, index)
            if (index < json.length && json[index] == '}') return null
            val key = parseString(json, index, MAX_KEY_CHARACTERS) ?: return null
            index = skipWhitespace(json, key.nextIndex)
            if (index >= json.length || json[index] != ':') return null
            val valueStart = skipWhitespace(json, index + 1)
            val valueEnd = skipValue(json, valueStart) ?: return null
            if (key.value == requestedField) return valueStart until valueEnd
            index = skipWhitespace(json, valueEnd)
            when {
                index >= json.length -> return null
                json[index] == ',' -> index++
                json[index] == '}' -> return null
                else -> return null
            }
        }
        return null
    }

    private fun parseString(json: String, startIndex: Int, maxCharacters: Int): ParsedString? {
        if (startIndex >= json.length || json[startIndex] != '"') return null
        val value = StringBuilder()
        var overflow = false
        var index = startIndex + 1
        while (index < json.length) {
            val character = json[index++]
            when (character) {
                '"' -> return ParsedString(value.takeUnless { overflow }?.toString(), index)
                '\\' -> {
                    if (index >= json.length) return null
                    val escaped = json[index++]
                    val decoded = when (escaped) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'
                        'f' -> '\u000C'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            if (index + 4 > json.length) return null
                            val codePoint = json.substring(index, index + 4).toIntOrNull(16) ?: return null
                            index += 4
                            codePoint.toChar()
                        }
                        else -> return null
                    }
                    if (value.length < maxCharacters) value.append(decoded) else overflow = true
                }
                else -> if (character.code < 0x20) {
                    return null
                } else if (value.length < maxCharacters) {
                    value.append(character)
                } else {
                    overflow = true
                }
            }
        }
        return null
    }

    private fun skipValue(json: String, startIndex: Int): Int? {
        if (startIndex >= json.length) return null
        if (json[startIndex] == '"') return parseString(json, startIndex, 0)?.nextIndex
        if (json[startIndex] != '{' && json[startIndex] != '[') {
            var index = startIndex
            while (index < json.length && json[index] != ',' && json[index] != '}') index++
            return index
        }

        val containers = ArrayDeque<Char>()
        var index = startIndex
        while (index < json.length) {
            when (json[index]) {
                '"' -> index = parseString(json, index, 0)?.nextIndex ?: return null
                '{' -> {
                    containers.addLast('}')
                    index++
                }
                '[' -> {
                    containers.addLast(']')
                    index++
                }
                '}', ']' -> {
                    if (containers.isEmpty() || containers.removeLast() != json[index]) return null
                    index++
                    if (containers.isEmpty()) return index
                }
                else -> index++
            }
        }
        return null
    }

    private fun skipWhitespace(value: String, startIndex: Int): Int {
        var index = startIndex
        while (index < value.length && value[index].isWhitespace()) index++
        return index
    }

    private data class ParsedString(
        val value: String?,
        val nextIndex: Int,
    )
}
