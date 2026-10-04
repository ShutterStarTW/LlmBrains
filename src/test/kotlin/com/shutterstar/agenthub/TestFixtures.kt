package com.shutterstar.agenthub

import com.shutterstar.agenthub.projects.model.AgentProject
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectIdentity
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path

/** A git-rooted project fixture at [root] that the given [agentIds] have worked on. */
internal fun project(root: Path, vararg agentIds: String): DiscoveredProject = DiscoveredProject(
    identity = ProjectIdentity("project", root.toString(), root.toString(), null),
    name = "project",
    path = root.toString(),
    gitRoot = root.toString(),
    gitRemote = null,
    currentBranch = null,
    agents = agentIds.map { AgentProject(it, "project", 1, null, emptyList()) },
    lastActivity = null,
)

/** Writes a minimal valid `SKILL.md` named [name] into [directory]. */
internal fun writeSkill(directory: Path, name: String) {
    Files.createDirectories(directory)
    Files.writeString(directory.resolve("SKILL.md"), "---\nname: $name\ndescription: Test skill\n---\nInstructions")
}

/** A project fixture with only a (possibly null) [path] and [gitRoot] — for sync-target path resolution tests. */
internal fun projectAt(path: String?, gitRoot: String? = null): DiscoveredProject = DiscoveredProject(
    identity = ProjectIdentity(id = "id", canonicalPath = null, gitRoot = null, gitRemote = null),
    name = "project",
    path = path,
    gitRoot = gitRoot,
    gitRemote = null,
    currentBranch = null,
    agents = emptyList(),
    lastActivity = null,
)

/** Writes [content] verbatim as `SKILL.md` into [directory] and returns [directory]. */
internal fun writeSkillMd(directory: Path, content: String): Path {
    Files.createDirectories(directory)
    Files.writeString(directory.resolve("SKILL.md"), content)
    return directory
}

/** [value] as a quoted, escaped JSON string literal. */
internal fun json(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

/** Every component below [root], depth first. */
internal fun descendants(root: Container): List<Component> = root.components.flatMap { component ->
    listOf(component) + if (component is Container) descendants(component) else emptyList()
}

/** Writes [content] to [path], creating parent directories, and returns [path]. */
internal fun writeFile(path: Path, content: String): Path {
    Files.createDirectories(path.parent)
    Files.writeString(path, content)
    return path
}
