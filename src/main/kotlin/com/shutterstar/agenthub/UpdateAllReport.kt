package com.shutterstar.agenthub

/** Per-agent results preserve failed and unverified updates for a later retry. */
internal data class UpdateAllReport(
    val updated: Int,
    val upToDate: Int,
    val failed: Int,
    val updatedNames: List<String>,
    val updatedIds: Set<String>,
    val upToDateIds: Set<String>,
    val failedIds: Set<String>,
) {
    fun remainingOutdated(current: Set<String>, attempted: Set<String>): List<String> =
        (current - ((updatedIds + upToDateIds - failedIds) intersect attempted)).sorted()

    companion object {
        fun parse(content: String): UpdateAllReport {
            val fields = content.lineSequence().filter { '=' in it }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            fun count(key: String) = fields[key]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            fun list(key: String, separator: String) =
                fields[key].orEmpty().split(separator).map(String::trim).filter(String::isNotEmpty)
            return UpdateAllReport(
                count("ok"), count("uptodate"), count("failed"), list("updated_names", "~"),
                list("updated_ids", ",").toSet(), list("uptodate_ids", ",").toSet(), list("failed_ids", ",").toSet(),
            )
        }
    }
}
