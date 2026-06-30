package com.shutterstar.agenthub

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object DetectionResultsWatcher {
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "llmbrains-watcher").also { it.isDaemon = true }
    }
    private val updateWatchTask = AtomicReference<ScheduledFuture<*>?>()
    private val versionWatchTask = AtomicReference<ScheduledFuture<*>?>()
    private val detectWatchTask = AtomicReference<ScheduledFuture<*>?>()
    private val availabilityTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()

    private const val INSTALL_POLL_INTERVAL_MS = 3_000L
    private const val INSTALL_MAX_WAIT_MS = 600_000L // 10 minutes

    private const val UPDATE_INITIAL_DELAY_MS = 30_000L

    fun watchCommandAvailability(
        project: Project,
        agent: CodingAgent,
        expectInstalled: Boolean,
        isUpdate: Boolean = false,
        onComplete: (() -> Unit)? = null,
    ) {
        val startTime = System.currentTimeMillis()
        val initialDelay = if (isUpdate) UPDATE_INITIAL_DELAY_MS else INSTALL_POLL_INTERVAL_MS
        val task = executor.scheduleAtFixedRate({
            try {
                val isInstalled = AgentDetector.isCommandAvailable(agent.command)
                val elapsed = System.currentTimeMillis() - startTime
                if (isInstalled == expectInstalled || elapsed > INSTALL_MAX_WAIT_MS) {
                    availabilityTasks.remove(agent.id)?.cancel(false)
                    // ModalityState.any() so the notification/refresh fires while the modal Settings dialog is open.
                    ApplicationManager.getApplication().invokeLater({
                        AgentSettingsState.getInstance().updateDetectionResult(agent.id, isInstalled)
                        if (!isUpdate) {
                            // Activate in the matching set: companions are opt-in, agents opt-out.
                            if (CompanionTools.isCompanion(agent.id)) {
                                AgentSettingsState.getInstance().setCompanionActive(agent.id, isInstalled)
                            } else {
                                AgentSettingsState.getInstance().setAgentActive(agent.id, isInstalled)
                            }
                        }
                        AgentSettingsConfigurable.scheduleRefresh()
                        val succeeded = isInstalled == expectInstalled
                        val type = if (succeeded) NotificationType.INFORMATION else NotificationType.WARNING
                        val action = when {
                            isUpdate -> "Update"
                            expectInstalled -> "Install"
                            else -> "Remove"
                        }
                        val msg = when {
                            isUpdate && succeeded -> "${agent.name} updated successfully"
                            isUpdate && !succeeded -> "${agent.name} update may have failed — run Detect to refresh"
                            succeeded && expectInstalled -> "${agent.name} installed successfully"
                            succeeded && !expectInstalled -> "${agent.name} removed successfully"
                            !succeeded && expectInstalled -> "${agent.name} installation may have failed — run Detect to refresh"
                            else -> "${agent.name} removal may have failed — run Detect to refresh"
                        }
                        showNotification(project, action, msg, type)
                        onComplete?.invoke()
                    }, ModalityState.any())
                }
            } catch (_: Exception) {
            }
        }, initialDelay, INSTALL_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        // Store-then-cancel keeps the map's "current task for this agent" atomic: a concurrent call
        // (e.g. double-click) can't land its task in between and have it overwritten.
        availabilityTasks.put(agent.id, task)?.cancel(false)
    }

    private const val POLL_INTERVAL_MS = 500L
    private const val MAX_WAIT_MS = 60_000L

    private fun pollFile(
        resultsFilePath: Path,
        taskRef: AtomicReference<ScheduledFuture<*>?>,
        onComplete: (String) -> Unit,
    ) {
        val startTime = System.currentTimeMillis()
        taskRef.get()?.cancel(false)
        val task = executor.scheduleAtFixedRate({
            try {
                if (Files.exists(resultsFilePath) && Files.size(resultsFilePath) > 0) {
                    val content = Files.readString(resultsFilePath)
                    if (!content.contains("done=1")) return@scheduleAtFixedRate
                    taskRef.getAndSet(null)?.cancel(false)
                    Files.deleteIfExists(resultsFilePath)
                    ApplicationManager.getApplication().invokeLater({ onComplete(content) }, ModalityState.any())
                } else if (System.currentTimeMillis() - startTime > MAX_WAIT_MS) {
                    taskRef.getAndSet(null)?.cancel(false)
                    Files.deleteIfExists(resultsFilePath)
                }
            } catch (_: Exception) {}
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        taskRef.set(task)
    }

    // Persists detection results only; does NOT touch active/inactive checkboxes.
    fun applyResults(results: Map<String, Boolean>): Pair<Int, Int> {
        AgentSettingsState.getInstance().saveDetectionResults(results)
        val installedCount = results.values.count { it }
        return Pair(installedCount, results.size)
    }

    fun watchForUpdateResults(
        resultsFilePath: Path,
        onComplete: (ok: Int, uptodate: Int, failed: Int, updatedNames: List<String>) -> Unit,
    ) {
        pollFile(resultsFilePath, updateWatchTask) { content ->
            val ok = parseIntValue(content, "ok")
            val uptodate = parseIntValue(content, "uptodate")
            val failed = parseIntValue(content, "failed")
            val updatedNames = findList(content, "updated_names", "~")
            onComplete(ok, uptodate, failed, updatedNames)
        }
    }

    // Parses the `detect-all` script's result file: one `id=0|1` line per agent, terminated by `done=1`.
    fun watchForDetectResults(
        project: Project,
        resultsFilePath: Path,
    ) {
        pollFile(resultsFilePath, detectWatchTask) { content ->
            val results = content.lines().mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = line.substring(0, idx).trim()
                if (key == "done") return@mapNotNull null
                when (line.substring(idx + 1).trim()) {
                    "1" -> key to true
                    "0" -> key to false
                    else -> null
                }
            }.toMap()
            if (results.isNotEmpty()) {
                val (installed, total) = applyResults(results)
                AgentSettingsConfigurable.scheduleRefresh()
                showNotification(project, "Detect", "$installed / $total agents installed", NotificationType.INFORMATION)
            }
        }
    }

    fun watchForVersionResults(
        project: Project,
        resultsFilePath: Path,
    ) {
        pollFile(resultsFilePath, versionWatchTask) { content ->
            val uptodate = parseIntValue(content, "uptodate")
            val updates = parseIntValue(content, "updates")
            val outdatedIds = findList(content, "outdated_ids", ",")
            AgentSettingsState.getInstance().saveOutdatedAgents(outdatedIds)
            AgentSettingsConfigurable.scheduleRefresh()
            val msg = if (updates > 0) {
                val names = (CodingAgents.all + CompanionTools.all).filter { it.id in outdatedIds }.map { it.name }
                val nameList = if (names.isNotEmpty()) ": ${names.joinToString(", ")}" else ""
                "$uptodate up to date · $updates ${if (updates == 1) "update" else "updates"} available$nameList"
            } else {
                allUpToDateMsg(uptodate)
            }
            val type = if (updates > 0) NotificationType.WARNING else NotificationType.INFORMATION
            showNotification(project, "Update", msg, type)
        }
    }

    private fun findValue(content: String, key: String): String? =
        content.lines()
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")?.trim()

    private fun findList(content: String, key: String, separator: String): List<String> =
        findValue(content, key)
            ?.split(separator)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun parseIntValue(content: String, key: String): Int =
        findValue(content, key)?.toIntOrNull() ?: 0

    fun allUpToDateMsg(count: Int): String =
        "All $count ${if (count == 1) "agent" else "agents"} up to date"

    fun showNotification(project: Project?, action: String, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AgentHub")
            .createNotification("AgentHub — $action", message, type)
            .notify(project)
    }
}
