package com.shutterstar.agenthub.environment.skills.discovery

internal data class SkillMetadata(
    val name: String?,
    val description: String?,
    val displayTitle: String? = null,
)

internal object SkillMetadataParser {
    private const val FRONTMATTER_DELIMITER = "---"
    private const val HEADING_PREFIX = "# "
    private val BLOCK_SCALAR_MARKERS = setOf("|", "|-", "|+", ">", ">-", ">+")

    fun parse(content: String): SkillMetadata {
        val lines = content.removePrefix("\uFEFF").lineSequence().toList()
        if (lines.firstOrNull()?.trim() != FRONTMATTER_DELIMITER) {
            return SkillMetadata(name = null, description = null, displayTitle = firstHeading(lines))
        }

        val closingIndex = lines
            .drop(1)
            .indexOfFirst { it.trim() == FRONTMATTER_DELIMITER }
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: return SkillMetadata(name = null, description = null, displayTitle = firstHeading(lines))

        val values = mutableMapOf<String, String>()
        var lineIndex = 1
        while (lineIndex < closingIndex) {
            val line = lines[lineIndex]
            val separatorIndex = line.indexOf(':')
            if (separatorIndex <= 0) {
                lineIndex++
                continue
            }

            val key = line.substring(0, separatorIndex).trim().lowercase()
            if (key != "name" && key != "description") {
                lineIndex++
                continue
            }

            val rawValue = line.substring(separatorIndex + 1).trim()
            if (rawValue in BLOCK_SCALAR_MARKERS) {
                val blockLines = mutableListOf<String>()
                lineIndex++
                while (lineIndex < closingIndex) {
                    val blockLine = lines[lineIndex]
                    if (blockLine.isNotBlank() && !blockLine.first().isWhitespace()) {
                        break
                    }
                    blockLines += blockLine.trim()
                    lineIndex++
                }
                values[key] = if (rawValue.startsWith('>')) {
                    blockLines.filter(String::isNotBlank).joinToString(" ")
                } else {
                    blockLines.joinToString("\n").trim()
                }
                continue
            }

            values[key] = unquote(rawValue)
            lineIndex++
        }

        return SkillMetadata(
            name = values["name"]?.trim()?.takeIf(String::isNotEmpty),
            description = values["description"]?.trim()?.takeIf(String::isNotEmpty),
            displayTitle = firstHeading(lines.drop(closingIndex + 1)),
        )
    }

    private fun firstHeading(lines: List<String>): String? = lines
        .firstOrNull { it.trimStart().startsWith(HEADING_PREFIX) }
        ?.trimStart()
        ?.removePrefix(HEADING_PREFIX)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun unquote(value: String): String {
        if (value.length < 2) {
            return value
        }
        val first = value.first()
        val last = value.last()
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            value.substring(1, value.lastIndex)
        } else {
            value
        }
    }
}
