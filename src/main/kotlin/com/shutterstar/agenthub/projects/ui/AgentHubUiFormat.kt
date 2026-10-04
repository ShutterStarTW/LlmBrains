package com.shutterstar.agenthub.projects.ui

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal object AgentHubUiFormat {
    val dateTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())

    private val sizeUnits = listOf("KB", "MB", "GB", "TB")

    /** Human-readable file size, e.g. "842 B", "45.6 MB", "1.2 GB". */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024 && unitIndex < sizeUnits.size - 1) {
            value /= 1024
            unitIndex++
        }
        return "%.1f %s".format(value, sizeUnits[unitIndex])
    }
}
