package com.shutterstar.agenthub.storage

import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncSettingsState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SharedStateChangeMonitorTest {
    @TempDir lateinit var directory: Path

    @Test fun `should notify once when another IDE saves settings`() {
        fun store() = SharedXmlStore(AgentHubHome(directory), "state/sync-settings.xml",
            SkillSyncSettingsState::class.java, ::SkillSyncSettingsState, statIntervalMillis = 0)
        val reader = store()
        val writer = store()
        val monitor = SharedStateChangeMonitor()
        assertFalse(monitor.observe(reader.stamp()))
        assertFalse(monitor.observe(reader.stamp()))
        writer.update { it.copy(preferredSyncMode = "COPY") }
        assertTrue(monitor.observe(reader.stamp()))
        assertFalse(monitor.observe(reader.stamp()))
    }
}
