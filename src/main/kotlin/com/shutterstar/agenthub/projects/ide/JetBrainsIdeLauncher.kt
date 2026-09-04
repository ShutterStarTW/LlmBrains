package com.shutterstar.agenthub.projects.ide

import java.nio.file.Files
import java.nio.file.Path

object JetBrainsIdeLauncher {
    fun launch(installation: JetBrainsIdeInstallation, projectDirectory: Path): Result<Unit> = launch(
        installation,
        projectDirectory,
    ) { command ->
        ProcessBuilder(command).start()
    }

    internal fun launch(
        installation: JetBrainsIdeInstallation,
        projectDirectory: Path,
        processStarter: (List<String>) -> Unit,
    ): Result<Unit> = runCatching {
        require(Files.isRegularFile(installation.launcher)) { "IDE launcher does not exist: ${installation.launcher}" }
        require(Files.isDirectory(projectDirectory)) { "Project directory does not exist: $projectDirectory" }
        processStarter(listOf(installation.launcher.toString(), projectDirectory.toString()))
    }
}
