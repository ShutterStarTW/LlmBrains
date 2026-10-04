package com.shutterstar.agenthub.environment.skills.sync.model

import java.nio.file.Path

/** Validates the user-facing "new name" as one direct child name, never as a path. */
internal object SkillDirectoryName {
    fun resolveSibling(targetPath: Path, name: String?): Path? {
        val value = name?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val component = runCatching { Path.of(value) }.getOrNull() ?: return null
        if (component.isAbsolute || component.nameCount != 1 || value == "." || value == "..") return null

        val parent = targetPath.toAbsolutePath().normalize().parent ?: return null
        val resolved = parent.resolve(component).normalize()
        return resolved.takeIf { it.parent == parent && it.fileName?.toString() == component.fileName?.toString() }
    }
}
