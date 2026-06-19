package com.shutterstar.agenthub

import java.util.Properties

private object VersionHolder

fun pluginVersion(): String {
    val props = Properties()
    VersionHolder::class.java.getResourceAsStream("/agenthub-version.properties")?.use { props.load(it) }
    return props.getProperty("version", "dev")
}
