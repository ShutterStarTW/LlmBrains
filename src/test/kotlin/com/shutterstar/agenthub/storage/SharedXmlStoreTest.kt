package com.shutterstar.agenthub.storage

import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncAuditState
import com.shutterstar.agenthub.environment.skills.sync.persistence.SkillSyncAuditEntryState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SharedXmlStoreTest {
    @TempDir lateinit var directory: Path

    private fun store(home: AgentHubHome = AgentHubHome(directory), timeout: Long = 3000) = SharedXmlStore(
        home, "state/audit.xml", SkillSyncAuditState::class.java, ::SkillSyncAuditState,
        statIntervalMillis = 0, lockTimeoutMillis = timeout,
    )

    @Test fun `should share durable state across fresh instances and detach snapshots`() {
        val first = store()
        first.update { it.copy(entries = mutableListOf(SkillSyncAuditEntryState(operationId = "first"))) }
        val second = store()
        assertEquals("first", second.snapshot().entries.single().operationId)
        second.snapshot().entries.clear()
        assertEquals(1, second.snapshot().entries.size)
        val before = first.stamp()
        second.update { it.copy(entries = (it.entries + SkillSyncAuditEntryState(operationId = "second")).toMutableList()) }
        assertTrue(first.stamp() != before)
        assertEquals(2, first.snapshot().entries.size)
        assertTrue(Files.exists(directory.resolve("format.json")))
    }

    @Test fun `should keep all updates made by two competing store instances`() {
        val a = store()
        val b = store()
        val pool = Executors.newFixedThreadPool(2)
        try {
            listOf(a, b).mapIndexed { index, instance -> pool.submit {
                repeat(30) { number ->
                    instance.update { state -> state.copy(entries = (state.entries + SkillSyncAuditEntryState(operationId = "$index-$number")).toMutableList()) }
                }
            } }.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals(60, store().snapshot().entries.map { it.operationId }.toSet().size)
    }

    @Test fun `should quarantine malformed empty and truncated XML without losing the original`() {
        val home = AgentHubHome(directory)
        home.prepare()
        listOf("", "<broken", "<wrong/>", "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///private'>]><x>&e;</x>").forEachIndexed { index, content ->
            Files.writeString(directory.resolve("state/audit.xml"), content)
            val snapshot = store(home).snapshot()
            assertTrue(snapshot.entries.isEmpty())
            Files.list(directory.resolve("state")).use { paths -> assertEquals((index + 1).toLong(), paths.filter { it.fileName.toString().contains(".corrupt-") }.count()) }
        }
    }

    @Test fun `should reject writes to newer schema and home formats without altering bytes`() {
        store().update { it }
        val path = directory.resolve("state/audit.xml")
        val newer = Files.readString(path).replace("version=\"1\"", "version=\"2\"")
        Files.writeString(path, newer)
        assertThrows(SharedStorageException::class.java) { store().update { it } }
        assertEquals(newer, Files.readString(path))
        Files.writeString(directory.resolve("format.json"), "{\"formatVersion\":2,\"minimumReaderVersion\":2}")
        assertThrows(SharedStorageException::class.java) { store().update { it } }
        assertEquals(newer, Files.readString(path))
    }

    @Test fun `should never write without a lock when another process owns it`() {
        val home = AgentHubHome(directory)
        home.prepare()
        FileChannel.open(directory.resolve("locks/state-audit.xml.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                assertThrows(SharedStorageException::class.java) { store(home, 50).update { it } }
            }
        }
        assertFalse(Files.exists(directory.resolve("state/audit.xml")))
    }

    @Test fun `should fall back to memory and warn once if the home cannot be created`() {
        val root = directory.resolve("not-a-directory")
        Files.writeString(root, "keep")
        val messages = mutableListOf<String>()
        val home = AgentHubHome(root, messages::add)
        val memory = store(home)
        memory.update { it.copy(entries = mutableListOf(SkillSyncAuditEntryState(operationId = "memory"))) }
        memory.update { it }
        assertEquals("memory", memory.snapshot().entries.single().operationId)
        assertEquals(1, messages.size)
        assertEquals("keep", Files.readString(root))
    }

    @Test fun `should isolate runtime cache paths and prefer the sandbox property over the environment`() {
        val userHome = directory.resolve("user")
        assertEquals(userHome.resolve(".agenthub"), AgentHubHome.resolvePath(userHome, null, null))
        assertEquals(directory.resolve("sandbox"), AgentHubHome.resolvePath(userHome, directory.resolve("real").toString(), directory.resolve("sandbox").toString()))
        assertFalse(AgentHubHome.runtimeDirectory("wsl:a/b") == AgentHubHome.runtimeDirectory("wsl:a?b"))
        assertFalse(AgentHubHome.runtimeDirectory("../../escape").contains(".."))
    }
}
