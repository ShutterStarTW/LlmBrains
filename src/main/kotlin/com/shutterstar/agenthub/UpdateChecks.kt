package com.shutterstar.agenthub

import com.shutterstar.agenthub.environment.mcp.discovery.JsonArray
import com.shutterstar.agenthub.environment.mcp.discovery.JsonObject
import com.shutterstar.agenthub.environment.mcp.discovery.JsonString
import com.shutterstar.agenthub.environment.mcp.discovery.SafeJsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Where the newest published version of an agent can be looked up, independent of how the agent
 * was installed on this machine (npm, native installer, brew, ...).
 */
data class VersionSource(val kind: Kind, val id: String) {
    enum class Kind(val prefix: String) { NPM("npm"), PYPI("pypi"), GITHUB("github") }

    companion object {
        private val WHITESPACE = "\\s+".toRegex()

        /** `npm:@scope/pkg`, `pypi:pkg` or `github:owner/repo`; null when malformed. */
        fun parse(spec: String): VersionSource? {
            val idx = spec.indexOf(':')
            if (idx <= 0) return null
            val kind = Kind.entries.firstOrNull { it.prefix == spec.substring(0, idx) } ?: return null
            val id = spec.substring(idx + 1).trim()
            if (id.isBlank()) return null
            return VersionSource(kind, if (kind == Kind.PYPI) normalizePypiName(id) else id)
        }

        /** Package-manager based update hints already name the package as their last non-flag token. */
        fun derive(updateHint: String): VersionSource? {
            val pkg = packageNameFrom(updateHint)
            if (pkg.isBlank()) return null
            return when {
                "npm" in updateHint -> VersionSource(Kind.NPM, pkg)
                "pip" in updateHint -> VersionSource(Kind.PYPI, normalizePypiName(pkg))
                else -> null
            }
        }

        // Drops flags (tokens starting with `-`) so trailing options like `--registry=...` aren't
        // mistaken for the package name; quotes and extras (`'litellm[proxy]'`) are stripped too.
        fun packageNameFrom(updateHint: String): String =
            updateHint.trim().split(WHITESPACE).filterNot { it.startsWith("-") }.lastOrNull()
                ?.trim('\'', '"')
                ?.substringBefore('[')
                ?: ""

        fun normalizePypiName(name: String): String = name.lowercase().replace('_', '-').replace('.', '-')
    }
}

/** Numeric dotted-version parsing/comparison; tolerant of surrounding text such as `2.1.282 (Claude Code)`. */
object VersionCompare {
    private val VERSION = "\\d+(?:\\.\\d+)+".toRegex()

    fun parse(text: String?): List<Long>? {
        val match = VERSION.find(text ?: return null)?.value ?: return null
        val parts = match.split('.').map { it.toLongOrNull() ?: return null }
        return parts
    }

    fun compare(a: List<Long>, b: List<Long>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val cmp = a.getOrElse(i) { 0L }.compareTo(b.getOrElse(i) { 0L })
            if (cmp != 0) return cmp
        }
        return 0
    }
}

enum class UpdateStatus { CURRENT, OUTDATED, UNKNOWN }

/** Compares the installed version (raw `--version` output) with the newest published one. */
fun classifyVersions(installedOutput: String?, latest: String?): UpdateStatus {
    val installed = VersionCompare.parse(installedOutput) ?: return UpdateStatus.UNKNOWN
    val newest = VersionCompare.parse(latest) ?: return UpdateStatus.UNKNOWN
    return if (VersionCompare.compare(newest, installed) > 0) UpdateStatus.OUTDATED else UpdateStatus.CURRENT
}

fun interface LatestVersionFetcher {
    /** Newest published version string, or null when it cannot be determined. */
    fun latest(source: VersionSource): String?
}

class HttpLatestVersionFetcher(private val timeout: Duration = Duration.ofSeconds(8)) : LatestVersionFetcher {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun latest(source: VersionSource): String? = try {
        when (source.kind) {
            VersionSource.Kind.NPM ->
                get("https://registry.npmjs.org/${source.id}/latest")?.let { firstString(it, "version") }
            VersionSource.Kind.PYPI ->
                get("https://pypi.org/pypi/${source.id}/json")?.let { firstString(it, "version") }
            VersionSource.Kind.GITHUB ->
                get("https://api.github.com/repos/${source.id}/releases/latest")?.let { firstString(it, "tag_name") }
        }
    } catch (_: Exception) {
        null
    }

    private fun get(url: String): String? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("User-Agent", "AgentHub-JetBrains-Plugin")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return if (response.statusCode() == 200) response.body() else null
    }

    private fun firstString(json: String, key: String): String? =
        "\"$key\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(json)?.groupValues?.get(1)
}

/** Includes the process exit status so query failure cannot masquerade as an empty report. */
data class CommandOutput(val output: String, val exitCode: Int?)

data class UpdateOutcome(
    /** Installed agents with a newer version available. */
    val outdated: List<String>,
    /** Installed agents whose update status could not be determined (no source, unparsable version, lookup failed). */
    val unverified: List<String>,
)

/**
 * Decides which installed agents are outdated. When an agent's package is managed by npm/pip on
 * this machine, the package manager's own `outdated` report is authoritative (it honours custom
 * registries and its version always matches the package). Everything else — natively installed
 * binaries, uv/pipx/brew installs — is checked by comparing `<command> --version` against the
 * newest version published at the agent's [VersionSource].
 *
 * [runCommand] runs a shell command line (in the active WSL distro / native shell) and returns its
 * combined output, or "" on failure.
 */
class UpdateChecker(
    private val runCommand: (String) -> String,
    private val fetcher: LatestVersionFetcher = HttpLatestVersionFetcher(),
    private val runCommandResult: ((String) -> CommandOutput)? = null,
) {
    private fun report(command: String): CommandOutput =
        runCommandResult?.invoke(command) ?: CommandOutput(runCommand(command), null)
    fun check(agents: List<CodingAgent>): UpdateOutcome {
        val sources = agents.associate { it.id to it.resolvedVersionSource }
        val npmAgents = agents.filter { sources[it.id]?.kind == VersionSource.Kind.NPM }
        val pypiAgents = agents.filter { sources[it.id]?.kind == VersionSource.Kind.PYPI }

        val npmInstalled = if (npmAgents.isEmpty()) emptySet() else npmInstalledNames(report("npm ls -g --depth=0 --json"))
        val npmManaged = npmAgents.any { sources[it.id]?.id in npmInstalled }
        val npmOutdated = if (npmManaged) npmOutdatedNames(report("npm outdated -g --json")) else emptySet()

        val pipInstalled = if (pypiAgents.isEmpty()) emptySet() else pipNames(report("pip list --format=json")).orEmpty()
        val pipManaged = pypiAgents.any { sources[it.id]?.id in pipInstalled }
        val pipOutdated = if (pipManaged) pipNames(report("pip list --outdated --format=json")) else emptySet()

        val pool = Executors.newFixedThreadPool(minOf(maxOf(agents.size, 1), 8))
        try {
            val futures = agents.map { agent ->
                val source = sources[agent.id]
                agent to pool.submit(Callable {
                    when {
                        source == null -> UpdateStatus.UNKNOWN
                        source.kind == VersionSource.Kind.NPM && source.id in npmInstalled ->
                            if (npmOutdated == null) UpdateStatus.UNKNOWN else if (source.id in npmOutdated) UpdateStatus.OUTDATED else UpdateStatus.CURRENT
                        source.kind == VersionSource.Kind.PYPI && source.id in pipInstalled ->
                            if (pipOutdated == null) UpdateStatus.UNKNOWN else if (source.id in pipOutdated) UpdateStatus.OUTDATED else UpdateStatus.CURRENT
                        else -> classifyVersions(
                            runCommand("${agent.command} ${agent.versionArgs}".trim()),
                            fetcher.latest(source),
                        )
                    }
                })
            }
            val outdated = mutableListOf<String>()
            val unverified = mutableListOf<String>()
            futures.forEach { (agent, future) ->
                val status = try {
                    future.get()
                } catch (_: ExecutionException) {
                    UpdateStatus.UNKNOWN
                }
                when (status) {
                    UpdateStatus.OUTDATED -> outdated += agent.id
                    UpdateStatus.UNKNOWN -> unverified += agent.id
                    UpdateStatus.CURRENT -> Unit
                }
            }
            return UpdateOutcome(outdated, unverified)
        } finally {
            pool.shutdownNow()
        }
    }


    private fun npmObject(result: CommandOutput): JsonObject? {
        if (result.exitCode != null && result.exitCode !in setOf(0, 1)) return null
        val text = result.output.trim()
        val start = text.indexOf('{')
        if (start < 0) return null
        val root = SafeJsonParser.parse(text.substring(start))
            as? JsonObject ?: return null
        return root.takeUnless { "error" in it.fields || "errors" in it.fields }
    }

    private fun npmInstalledNames(result: CommandOutput): Set<String> {
        val root = npmObject(result) ?: return emptySet()
        val dependencies = root.fields["dependencies"]
            as? JsonObject ?: return emptySet()
        return dependencies.fields.filterValues {
            it is JsonObject
        }.keys
    }

    private fun npmOutdatedNames(result: CommandOutput): Set<String>? {
        val root = npmObject(result) ?: return null
        if (root.fields.values.any { it !is JsonObject }) return null
        return root.fields.keys
    }

    private fun pipNames(result: CommandOutput): Set<String>? {
        if (result.exitCode != null && result.exitCode != 0) return null
        val text = result.output.trim()
        val start = text.indexOf('[')
        if (start < 0) return null
        val array = SafeJsonParser.parse(text.substring(start))
            as? JsonArray ?: return null
        val names = mutableSetOf<String>()
        for (entry in array.values) {
            val name = ((entry as? JsonObject)
                ?.fields?.get("name") as? JsonString)
                ?.value?.takeIf(String::isNotBlank) ?: return null
            names += VersionSource.normalizePypiName(name)
        }
        return names
    }
}
