package com.shutterstar.agenthub.projects.ui

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal object AgentHubUiFormat {
    val dateTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
}
