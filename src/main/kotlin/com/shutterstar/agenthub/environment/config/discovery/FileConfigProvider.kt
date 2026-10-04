package com.shutterstar.agenthub.environment.config.discovery

import com.shutterstar.agenthub.environment.config.model.AgentConfigSource
import com.shutterstar.agenthub.environment.config.model.ConfigFormat
import com.shutterstar.agenthub.environment.config.model.ConfigKind
import com.shutterstar.agenthub.environment.config.model.ConfigScope
import com.shutterstar.agenthub.environment.discovery.PROJECT_WALK_EXCLUDED_DIRECTORY_NAMES
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/** Exact filenames only. Never follows links or enumerates credentials, commands or arbitrary JSON. */
abstract class FileConfigProvider(
    final override val agentId: String,
    private val globalPaths: List<Path>,
    private val projectPaths: List<String>,
) : ConfigProvider {
    override fun discoverGlobal(): List<AgentConfigSource> = globalPaths.distinct().mapNotNull {
        inventory(it, ConfigScope.GLOBAL, null)
    }

    override fun discoverProject(project: DiscoveredProject): List<AgentConfigSource> {
        if (projectPaths.isEmpty()) return emptyList()
        val root = ProjectPathResolver.resolveExistingRoot(project)?.toAbsolutePath()?.normalize() ?: return emptyList()
        if (!safePath(root)) return emptyList()
        val results = mutableListOf<AgentConfigSource>()
        var visited = 0
        Files.walkFileTree(root, emptySet(), MAX_DEPTH + 1, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (++visited > MAX_ENTRIES || Thread.currentThread().isInterrupted) return FileVisitResult.TERMINATE
                if (dir != root && dir.fileName.toString().lowercase(Locale.ROOT) in PROJECT_WALK_EXCLUDED_DIRECTORY_NAMES) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                projectPaths.forEach { relative ->
                    inventory(dir.resolve(relative), ConfigScope.PROJECT, project.name)?.let(results::add)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (++visited > MAX_ENTRIES || Thread.currentThread().isInterrupted) FileVisitResult.TERMINATE
                else FileVisitResult.CONTINUE

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.SKIP_SUBTREE
        })
        return results.distinctBy { it.path }
    }

    private fun inventory(path: Path, scope: ConfigScope, projectName: String?): AgentConfigSource? {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.exists(normalized, NOFOLLOW_LINKS) || !safePath(normalized) || !Files.isRegularFile(normalized, NOFOLLOW_LINKS)) return null
        val attrs = Files.readAttributes(normalized, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val format = when (normalized.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "json", "jsonc" -> ConfigFormat.JSON
            "toml" -> ConfigFormat.TOML
            "yaml", "yml" -> ConfigFormat.YAML
            else -> ConfigFormat.OTHER
        }
        val kind = when {
            normalized.fileName.toString().contains("permissions") || normalized.endsWith(".cursor/cli.json") -> ConfigKind.PERMISSIONS
            normalized.fileName.toString() == "hooks.json" -> ConfigKind.HOOKS
            normalized.fileName.toString() == ".claude.json" -> ConfigKind.OTHER
            else -> ConfigKind.SETTINGS
        }
        val highlights = if (attrs.size() <= MAX_CONTENT_BYTES) {
            // Bounded even if the file grows between stat and read. No content or exceptions are logged.
            try {
                Files.newInputStream(normalized, NOFOLLOW_LINKS).use { input ->
                    val bytes = input.readNBytes(MAX_CONTENT_BYTES + 1)
                    if (bytes.size > MAX_CONTENT_BYTES) emptyList()
                    else ConfigHighlightReader.read(
                        agentId, format, String(bytes, Charsets.UTF_8), normalized.toString().endsWith(".jsonc"),
                    )
                }
            } catch (_: IOException) {
                emptyList()
            }
        } else emptyList()
        return AgentConfigSource(
            agentId = agentId,
            path = normalized.toString(),
            scope = scope,
            kind = kind,
            format = format,
            sizeBytes = attrs.size(),
            modifiedAtEpochMillis = attrs.lastModifiedTime().toMillis(),
            highlights = highlights,
            projectName = projectName,
        )
    }

    private fun safePath(path: Path): Boolean = generateSequence(path.toAbsolutePath().normalize()) { it.parent }
        .none { Files.isSymbolicLink(it) || Files.readAttributes(it, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isOther }

    companion object {
        const val MAX_CONTENT_BYTES = 1024 * 1024
        const val MAX_DEPTH = 8
        const val MAX_ENTRIES = 20_000
    }
}
