package com.shutterstar.agenthub.projects.resolve

import com.shutterstar.agenthub.projects.model.ProjectIdentity
import com.shutterstar.agenthub.projects.model.RawAgentProject
import java.io.File
import java.net.URI
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

data class ResolvedProject(
    val identity: ProjectIdentity,
    val name: String,
    val path: String?,
    val currentBranch: String?,
)

class ProjectResolver(
    private val gitProjectLookup: (String) -> GitProjectInfo? = GitProjectResolver()::resolve,
) {
    private val gitCache = ConcurrentHashMap<String, Optional<GitProjectInfo>>()
    private val projectCache = ConcurrentHashMap<String, ResolvedProject>()

    fun resolve(raw: RawAgentProject): ProjectIdentity = resolveProject(raw).identity

    fun resolveProject(raw: RawAgentProject): ResolvedProject {
        val pathCacheKey = raw.rawProjectPath?.trim()?.takeIf { it.isNotEmpty() }
        return if (pathCacheKey == null) {
            resolveProjectUncached(raw)
        } else {
            projectCache.computeIfAbsent(pathCacheKey) { resolveProjectUncached(raw) }
        }
    }

    private fun resolveProjectUncached(raw: RawAgentProject): ResolvedProject {
        val canonicalPath = normalizeFilesystemPath(raw.rawProjectPath)
        val gitInfo = canonicalPath?.let { path ->
            gitCache.computeIfAbsent(path) { Optional.ofNullable(gitProjectLookup(path)) }.orElse(null)
        }
        val gitRoot = normalizeFilesystemPath(gitInfo?.root)
        val gitRemote = normalizeGitRemote(gitInfo?.remote)
        val identitySource = when {
            gitRemote != null -> "remote:$gitRemote"
            gitRoot != null -> "git-root:$gitRoot"
            canonicalPath != null -> "path:$canonicalPath"
            else -> "unresolved:${raw.agentId}:${raw.sessionId}:${raw.sourcePath.orEmpty()}"
        }
        val identity = ProjectIdentity(
            id = stableId(identitySource),
            canonicalPath = canonicalPath,
            gitRoot = gitRoot,
            gitRemote = gitRemote,
        )
        val projectPath = gitRoot ?: canonicalPath
        return ResolvedProject(
            identity = identity,
            name = pathName(projectPath)
                ?: gitRemote?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "Unknown Project",
            path = projectPath,
            currentBranch = gitInfo?.currentBranch,
        )
    }

    companion object {
        private val WINDOWS_DRIVE = Regex("^[A-Za-z]:/")
        private val WSL_MOUNT = Regex("^/mnt/([A-Za-z])(?:/(.*))?$")
        private val SCP_REMOTE = Regex("^(?:[^@/]+@)?([^:/]+):(.+)$")

        fun normalizeFilesystemPath(rawPath: String?): String? {
            var value = rawPath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            value = fileUriPath(value) ?: value
            value = value.replace('\\', '/')
            if (File.separatorChar == '\\') {
                val wslMatch = WSL_MOUNT.matchEntire(value)
                if (wslMatch != null) {
                    val drive = wslMatch.groupValues[1].uppercase()
                    val suffix = wslMatch.groupValues[2]
                    value = if (suffix.isEmpty()) "$drive:/" else "$drive:/$suffix"
                }
            }
            value = expandHome(value)

            val canonical = try {
                when {
                    WINDOWS_DRIVE.containsMatchIn(value) && File.separatorChar != '\\' -> normalizePortablePath(value)
                    value.startsWith('/') && File.separatorChar == '\\' -> normalizePortablePath(value)
                    else -> {
                        val path = Path.of(value)
                        val normalized = if (path.toFile().exists()) path.toRealPath() else path.toAbsolutePath().normalize()
                        normalized.toString().replace('\\', '/')
                    }
                }
            } catch (_: InvalidPathException) {
                normalizePortablePath(value)
            } catch (_: SecurityException) {
                normalizePortablePath(value)
            } catch (_: java.io.IOException) {
                normalizePortablePath(value)
            }
            return normalizeDriveLetter(trimTrailingSeparators(canonical))
                .takeIf { it.isNotBlank() }
        }

        fun normalizeGitRemote(rawRemote: String?): String? {
            val remote = rawRemote?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val scp = SCP_REMOTE.matchEntire(remote)
            if (scp != null && "://" !in remote && !WINDOWS_DRIVE.containsMatchIn(remote.replace('\\', '/'))) {
                val host = scp.groupValues[1].lowercase()
                return cleanRemotePath("$host/${scp.groupValues[2]}")
            }

            val uri = runCatching { URI(remote) }.getOrNull()
            if (uri?.scheme != null) {
                if (uri.scheme.equals("file", ignoreCase = true)) {
                    return normalizeFilesystemPath(uri.path)
                }
                val host = uri.host?.lowercase()
                if (host != null) {
                    val authority = if (uri.port >= 0) "$host:${uri.port}" else host
                    return cleanRemotePath(authority + "/" + uri.path.orEmpty().trimStart('/'))
                }
            }

            val withoutCredentials = remote.replace(Regex("(?<=://)[^/@]+@"), "")
            val withoutScheme = withoutCredentials.substringAfter("://", withoutCredentials)
            val host = withoutScheme.substringBefore('/').lowercase()
            val path = withoutScheme.substringAfter('/', "")
            return cleanRemotePath(if (path.isEmpty()) host else "$host/$path")
        }

        private fun stableId(source: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            return "project-" + bytes.take(16).joinToString("") { "%02x".format(it) }
        }

        private fun fileUriPath(value: String): String? =
            if (value.startsWith("file:", ignoreCase = true)) {
                runCatching { Path.of(URI(value)).toString() }.getOrNull()
            } else {
                null
            }

        private fun expandHome(value: String): String = when {
            value == "~" -> System.getProperty("user.home")
            value.startsWith("~/") -> System.getProperty("user.home") + value.drop(1)
            else -> value
        }

        private fun normalizePortablePath(value: String): String {
            val drive = WINDOWS_DRIVE.find(value)?.value?.take(2)
            val absolute = value.startsWith('/') || drive != null
            val remainder = if (drive != null) value.drop(2).trimStart('/') else value.trimStart('/')
            val parts = ArrayDeque<String>()
            remainder.split('/').forEach { part ->
                when (part) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeLast() else if (!absolute) parts.addLast(part)
                    else -> parts.addLast(part)
                }
            }
            val prefix = when {
                drive != null -> drive.uppercase() + "/"
                absolute -> "/"
                else -> ""
            }
            return prefix + parts.joinToString("/")
        }

        private fun trimTrailingSeparators(value: String): String {
            if (value == "/" || Regex("^[A-Za-z]:/$").matches(value)) return value
            return value.trimEnd('/')
        }

        private fun normalizeDriveLetter(value: String): String =
            if (WINDOWS_DRIVE.containsMatchIn(value)) value[0].uppercaseChar() + value.substring(1) else value

        private fun cleanRemotePath(value: String): String {
            var cleaned = value.replace('\\', '/').substringBefore('?').substringBefore('#').trim('/')
            if (cleaned.endsWith(".git", ignoreCase = true)) cleaned = cleaned.dropLast(4)
            return cleaned.trimEnd('/')
        }

        private fun pathName(path: String?): String? = path
            ?.trimEnd('/')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && !Regex("^[A-Za-z]:$").matches(it) }
    }
}
