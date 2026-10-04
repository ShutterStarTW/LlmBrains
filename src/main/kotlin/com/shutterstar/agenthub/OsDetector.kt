package com.shutterstar.agenthub

import java.util.Locale

object OsDetector {
    enum class OsType {
        WINDOWS,
        MAC,
        LINUX,
        OTHER
    }

    val currentOs: OsType by lazy {
        val osName = System.getProperty("os.name", "").lowercase(Locale.ENGLISH)
        when {
            osName.contains("win") -> OsType.WINDOWS
            osName.contains("mac") || osName.contains("darwin") -> OsType.MAC
            osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> OsType.LINUX
            else -> OsType.OTHER
        }
    }

    fun isWindows(): Boolean = currentOs == OsType.WINDOWS
    fun isMac(): Boolean = currentOs == OsType.MAC

    /** Key for comparing paths: Windows file systems are case-insensitive. */
    fun pathKey(path: String): String = if (isWindows()) path.lowercase(Locale.ROOT) else path
}
