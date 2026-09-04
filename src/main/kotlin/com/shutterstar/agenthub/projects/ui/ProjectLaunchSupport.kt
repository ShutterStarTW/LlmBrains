package com.shutterstar.agenthub.projects.ui

import com.shutterstar.agenthub.projects.model.DiscoveredProject
import com.shutterstar.agenthub.projects.model.ProjectPathResolver
import java.nio.file.Path

object ProjectLaunchSupport {
    fun workingDirectory(project: DiscoveredProject): Path? = ProjectPathResolver.resolveExistingRoot(project)
}
