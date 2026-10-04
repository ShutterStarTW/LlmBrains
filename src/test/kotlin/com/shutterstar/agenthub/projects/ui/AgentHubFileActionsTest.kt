package com.shutterstar.agenthub.projects.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AgentHubFileActionsTest {
    @TempDir lateinit var root: Path

    @Test fun `should open the skill directory itself instead of its parent`() {
        val directory = Files.createDirectories(root.resolve("skills/example"))
        assertEquals(directory.toRealPath(), AgentHubFileActions.directoryToReveal(directory))
    }

    @Test fun `should reveal the containing directory for a skill file`() {
        val directory = Files.createDirectories(root.resolve("skills/example"))
        val file = Files.writeString(directory.resolve("SKILL.md"), "# Example")
        assertEquals(directory.toRealPath(), AgentHubFileActions.directoryToReveal(file))
    }

    @Test fun `directory open support is resolved without calling a method that older platforms lack`() {
        // Must not throw on any platform build; the answer itself depends on the host.
        AgentHubFileActions.isDirectoryOpenSupported()
    }
}
