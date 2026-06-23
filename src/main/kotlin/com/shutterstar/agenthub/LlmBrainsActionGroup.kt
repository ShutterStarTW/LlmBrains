package com.shutterstar.agenthub

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon

class LlmBrainsActionGroup : ActionGroup("AgentHub", "Open any CLI coding agent in a new terminal window.", null), DumbAware {
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project
        val actions = mutableListOf<AnAction>()
        val settings = AgentSettingsState.getInstance()
        val activeAgents = settings.activeAgents()
        val detectionResults = settings.getDetectionResults()
        val installedAgents = if (detectionResults != null)
            CodingAgents.available().filter { detectionResults[it.id] == true }
        else
            activeAgents
        activeAgents.forEach { agent ->
            actions += AgentDirectAction(agent, project)
        }

        val customAgent = settings.getCustomAgent()
        if (customAgent != null) {
            if (activeAgents.isNotEmpty()) {
                actions += Separator.getInstance()
            }
            actions += AgentDirectAction(customAgent, project)
        }

        if (activeAgents.isNotEmpty() || customAgent != null) {
            actions += Separator.getInstance()
        }
        actions += SimpleRunAction("Agent settings…", AllIcons.General.Settings) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "AgentHub")
        }
        actions += Separator.getInstance()
        actions += SimpleRunAction("Detect installed agents", AllIcons.Actions.Find) {
            if (settings.getState().runInBackground) {
                AgentDetector.detectAndNotify(project)
            } else {
                project?.let { proj ->
                    val tempFile = Files.createTempFile("llmbrains-detect-", ".txt")
                    val command = buildDetectScript(CodingAgents.available(), tempFile)
                    TerminalCommandRunner.run(proj, "🔍 Detect Agents", command)
                    DetectionResultsWatcher.watchForDetectResults(proj, tempFile)
                }
            }
        }
        actions += SimpleRunAction("Check for updates", AllIcons.Actions.Refresh) {
            project?.let { proj ->
                val tempFile = Files.createTempFile("llmbrains-version-", ".txt")
                val command = buildVersionAllScript(installedAgents + listOfNotNull(customAgent), tempFile)
                TerminalCommandRunner.runRespectingSettings(proj, "Check Updates", "📋 Check Updates", command)
                DetectionResultsWatcher.watchForVersionResults(proj, tempFile)
            }
        }
        actions += SimpleRunAction("Update all agents", AllIcons.Actions.Download) {
            project?.let { proj ->
                val tempFile = Files.createTempFile("llmbrains-update-", ".txt")
                val command = buildUpdateScript(installedAgents, tempFile)
                TerminalCommandRunner.runRespectingSettings(proj, "Update Agents", "🔄 Update Agents", command)
                DetectionResultsWatcher.watchForUpdateResults(tempFile) { ok, uptodate, failed, updatedNames: List<String> ->
                    AgentSettingsState.getInstance().saveOutdatedAgents(emptyList())
                    AgentSettingsConfigurable.scheduleRefresh()
                    val type = if (failed > 0) NotificationType.WARNING else NotificationType.INFORMATION
                    val msg = when {
                        updatedNames.isNotEmpty() -> "Updated: ${updatedNames.joinToString(", ")} · $uptodate up to date" + if (failed > 0) " · $failed failed" else ""
                        ok == 0 && failed == 0 -> DetectionResultsWatcher.allUpToDateMsg(uptodate)
                        else -> "$ok updated · $uptodate up to date" + if (failed > 0) " · $failed failed" else ""
                    }
                    DetectionResultsWatcher.showNotification(proj, "Update", msg, type)
                }
            }
        }
        actions += Separator.getInstance()
        actions += SimpleLabelAction("AgentHub v${pluginVersion()}")
        return actions.toTypedArray()
    }

    // Each element of [args] is passed as a separate quoted argument; escaping is handled here.
    private fun buildScript(subcommand: String, args: List<String>): String {
        return if (OsDetector.isWindows()) {
            val scriptPath = LlmBrainsScriptInstaller.powershellScriptPath()
                ?: return """powershell -Command "Write-Host 'AgentHub: script installation failed — check IDE logs'""""
            val escapedPath = escapeForPowerShell(scriptPath.toString())
            val quotedArgs = args.joinToString(" ") { "'${escapeForPowerShell(it)}'" }
            // -File (not -Command "...") so the terminal's PowerShell parses this line only once.
            // -Command "& '...' $args" would re-parse $quotedArgs as script text, and agent payloads
            // routinely contain unescaped `"`, `$`, and `|` (e.g. forge/goose/plandex install hints)
            // that would break out of the outer double-quoted -Command string.
            """powershell -NoProfile -File '$escapedPath' $subcommand $quotedArgs"""
        } else {
            val scriptPath = LlmBrainsScriptInstaller.bashScriptPath()
                ?: return """echo 'AgentHub: script installation failed — check IDE logs'"""
            val escapedPath = escapeForDoubleQuotes(scriptPath.toString())
            val quotedArgs = args.joinToString(" ") { "\"${escapeForDoubleQuotes(it)}\"" }
            """bash -lc 'SCRIPT_PATH="$escapedPath"; PATH="$(dirname "${'$'}SCRIPT_PATH"):${'$'}PATH"; llmbrains.sh $subcommand $quotedArgs'"""
        }
    }

    // Pipe-delimited agent record. [agent.platformInstallHint] may itself contain `|` (e.g. `irm ... | iex`),
    // so it is always placed LAST — the scripts' parsing assigns the line remainder to the final field.
    private fun agentPayload(agent: CodingAgent): String =
        "${agent.id}|${agent.name}|${agent.command}|${agent.versionArgs}|${agent.updateHint}|${agent.platformInstallHint}"

    // The payload routinely contains raw `"`, `$`, and `|` that get mangled if passed inline as a
    // command-line argument, so the scripts read it from this temp file instead (and delete it after).
    private fun writeDataFile(prefix: String, data: String): Path {
        val file = Files.createTempFile(prefix, ".txt")
        Files.writeString(file, data)
        return file
    }

    private fun buildVersionAllScript(agents: List<CodingAgent>, outputFile: Path): String {
        val data = agents.joinToString("~") { agentPayload(it) }
        val dataFile = writeDataFile("llmbrains-version-data-", data)
        return buildScript("version-all", listOf(dataFile.toString(), outputFile.toString()))
    }

    private fun buildDetectScript(agents: List<CodingAgent>, outputFile: Path): String {
        val data = agents.joinToString("~") { agentPayload(it) }
        val dataFile = writeDataFile("llmbrains-detect-data-", data)
        return buildScript("detect-all", listOf(dataFile.toString(), outputFile.toString()))
    }

    private fun buildUpdateScript(agents: List<CodingAgent>, outputFile: Path): String {
        val data = agents.joinToString("~") { agentPayload(it) }
        val dataFile = writeDataFile("llmbrains-update-data-", data)
        val activeIds = agents.joinToString(",") { it.id }
        return buildScript("update-all", listOf(dataFile.toString(), activeIds, outputFile.toString()))
    }

    private fun escapeForDoubleQuotes(value: String): String {
        val builder = StringBuilder(value.length)
        value.forEach { char ->
            when (char) {
                '\\', '"', '$', '`' -> builder.append('\\').append(char)
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }

    private fun escapeForPowerShell(value: String): String {
        return value.replace("'", "''")
    }

    private inner class AgentDirectAction(
        private val agent: CodingAgent,
        private val project: Project?,
    ) : AnAction(agent.name), DumbAware {

        override fun update(e: AnActionEvent) {
            val icon = FaviconLoader.get(agent)
            if (icon != null) e.presentation.icon = icon

            val installed = AgentSettingsState.getInstance().getDetectionResults()?.get(agent.id)
            if (installed == false) {
                e.presentation.text = "${agent.name} (not installed)"
            } else {
                e.presentation.text = agent.name
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val proj = project ?: return
            val installed = AgentSettingsState.getInstance().getDetectionResults()?.get(agent.id)
            if (installed == false && agent.platformInstallHint.isNotBlank()) {
                val background = AgentSettingsState.getInstance().getState().runInBackground
                val confirmed = TerminalCommandRunner.confirmRun(
                    proj,
                    "Install ${agent.name}",
                    agent.platformInstallHint,
                    context = "${agent.name} is not installed yet.",
                    background = background,
                )
                if (!confirmed) return
                val label = "📦 Install ${agent.name}"
                TerminalCommandRunner.runRespectingSettings(proj, label, label, agent.platformInstallHint)
                DetectionResultsWatcher.watchCommandAvailability(proj, agent, expectInstalled = true) {
                    if (background && AgentSettingsState.getInstance().getDetectionResults()?.get(agent.id) == true) {
                        TerminalCommandRunner.run(proj, "🤖 ${agent.name}", agent.command)
                    }
                }
            } else {
                TerminalCommandRunner.run(proj, "🤖 ${agent.name}", agent.command)
            }
        }
    }

    private class SimpleRunAction(
        text: String,
        private val icon: Icon? = null,
        val runner: () -> Unit,
    ) : AnAction(text), DumbAware {
        override fun update(e: AnActionEvent) {
            if (icon != null) e.presentation.icon = icon
        }
        override fun actionPerformed(e: AnActionEvent) = runner()
    }

    private class SimpleLabelAction(text: String) : AnAction(text), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {}
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }
    }
}