package com.shutterstar.agenthub.projects.ui

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.shutterstar.agenthub.DetectionResultsWatcher
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path

/**
 * Open / reveal / copy for a discovered file or directory (a session transcript, an MCP config, an
 * instruction file). Filesystem checks run on a pooled thread; the IDE call and the "no longer
 * available" notification are marshalled back to the EDT. Nothing here reads file contents — the
 * IDE editor opens the file at the user's request, so the tool window itself still never renders
 * secrets.
 */
internal object AgentHubFileActions {
    /** Opens a regular file in the editor; a directory is revealed in the OS file manager instead. */
    fun open(project: Project, rawPath: String?, title: String, missingMessage: String, isDisposed: () -> Boolean = { false }) {
        val path = toPath(rawPath) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val isFile = runCatching { Files.isRegularFile(path) }.getOrDefault(false)
            val isDirectory = !isFile && runCatching { Files.isDirectory(path) }.getOrDefault(false)
            val file = if (isFile) runCatching { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) }.getOrNull() else null
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed() || project.isDisposed) return@invokeLater
                when {
                    file != null -> FileEditorManager.getInstance(project).openFile(file, true)
                    isDirectory -> revealDirectoryOrFile(path)
                    else -> DetectionResultsWatcher.showNotification(project, title, missingMessage, NotificationType.WARNING)
                }
            }, ModalityState.any())
        }
    }

    fun reveal(project: Project, rawPath: String?, title: String, missingMessage: String, isDisposed: () -> Boolean = { false }) {
        val path = toPath(rawPath) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val isDirectory = runCatching { Files.isDirectory(path) }.getOrDefault(false)
            val exists = isDirectory || runCatching { Files.exists(path) }.getOrDefault(false)
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed() || project.isDisposed) return@invokeLater
                if (exists) {
                    if (isDirectory) revealDirectoryOrFile(path) else RevealFileAction.openFile(path.toFile())
                } else {
                    DetectionResultsWatcher.showNotification(project, title, missingMessage, NotificationType.WARNING)
                }
            }, ModalityState.any())
        }
    }

    /**
     * `RevealFileAction.openFile` treats its argument as an item to select *inside its parent* -
     * given a directory (a skill/session folder), that opens the folder ABOVE it with the target
     * merely highlighted, one extra click away from actually being inside it. `openDirectory`
     * opens the target's own contents directly, which is what "reveal this folder" means for a
     * directory; [isDirectoryOpenSupported] guards the rare platform where it isn't available.
     */
    internal fun revealDirectoryOrFile(path: Path) {
        if (isDirectoryOpenSupported()) RevealFileAction.openDirectory(path) else RevealFileAction.openFile(path.toFile())
    }

    /**
     * `RevealFileAction.isDirectoryOpenSupported()` only exists in newer platforms (it is missing in 2024.2, where a
     * direct call fails the compatibility check), so it is looked up by reflection; older builds fall back to
     * `isSupported()`, which is what the newer method refines.
     */
    internal fun isDirectoryOpenSupported(): Boolean =
        runCatching { RevealFileAction::class.java.getMethod("isDirectoryOpenSupported").invoke(null) as Boolean }
            .getOrElse { RevealFileAction.isSupported() }

    /** Resolves links and junctions, and accepts either a directory or a file inside it. */
    internal fun directoryToReveal(path: Path): Path? {
        val realPath = path.toRealPath()
        return when {
            Files.isDirectory(realPath) -> realPath
            Files.isRegularFile(realPath) -> realPath.parent
            else -> null
        }
    }

    fun copyPath(rawPath: String?) {
        val path = rawPath?.takeIf { it.isNotBlank() } ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(path))
    }

    private fun toPath(rawPath: String?): Path? = rawPath?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }
}
