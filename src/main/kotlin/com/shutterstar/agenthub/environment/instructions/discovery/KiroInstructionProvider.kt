package com.shutterstar.agenthub.environment.instructions.discovery

import com.shutterstar.agenthub.environment.discovery.EnvHomeDirectorySupport
import com.shutterstar.agenthub.environment.instructions.model.InstructionScope
import com.shutterstar.agenthub.environment.instructions.model.InstructionSource
import com.shutterstar.agenthub.environment.instructions.model.InstructionType
import com.shutterstar.agenthub.projects.model.DiscoveredProject
import java.nio.file.Path
import kotlin.io.path.extension
import com.shutterstar.agenthub.AgentRuntime

class KiroInstructionProvider(
    private val homeDirectory: Path = AgentRuntime.userHome(),
) : InstructionProvider {
    override val agentId: String = "kiro"
    private val kiroDirectory = EnvHomeDirectorySupport.resolveGuarded("KIRO_HOME", homeDirectory, ".kiro")

    override fun discoverGlobal(): List<InstructionSource> =
        discoverSteering(kiroDirectory.resolve("steering"), InstructionScope.GLOBAL)

    override fun discoverProject(project: DiscoveredProject): List<InstructionSource> {
        val root = InstructionFileSupport.projectRoot(project) ?: return emptyList()
        return InstructionFileSupport.scan(root) { scanRoot, file ->
            file.fileName.toString() == "AGENTS.md" ||
                (file.extension.equals("md", ignoreCase = true) &&
                    InstructionFileSupport.isWithinDirectory(scanRoot, file, ".kiro", "steering"))
        }.mapNotNull { path ->
            val type = if (path.fileName.toString() == "AGENTS.md") {
                InstructionType.AGENTS_MD
            } else {
                InstructionType.AGENT_SPECIFIC
            }
            InstructionFileSupport.source(path, InstructionScope.PROJECT, agentId, type, project.name)
        }
    }

    private fun discoverSteering(root: Path, scope: InstructionScope): List<InstructionSource> =
        InstructionFileSupport.scan(root) { _, file -> file.extension.equals("md", ignoreCase = true) }
            .mapNotNull { path -> InstructionFileSupport.source(path, scope, agentId, InstructionType.AGENT_SPECIFIC) }
}
