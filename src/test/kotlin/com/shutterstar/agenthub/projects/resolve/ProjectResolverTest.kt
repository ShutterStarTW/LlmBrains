package com.shutterstar.agenthub.projects.resolve

import com.shutterstar.agenthub.projects.model.RawAgentProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class ProjectResolverTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `trailing separator resolves to the same project`() {
        val project = Files.createDirectories(tempDirectory.resolve("project"))
        val resolver = ProjectResolver { null }

        val plain = resolver.resolve(raw(project.toString(), "one"))
        val trailing = resolver.resolve(raw(project.toString() + project.fileSystem.separator, "two"))

        assertEquals(plain.id, trailing.id)
        assertEquals(plain.canonicalPath, trailing.canonicalPath)
    }

    @Test
    fun `ssh and https remotes normalize to the same identity`() {
        val first = Files.createDirectories(tempDirectory.resolve("first"))
        val second = Files.createDirectories(tempDirectory.resolve("second"))
        val resolver = ProjectResolver { path ->
            val remote = if (path.endsWith("first")) {
                "git@github.com:ShutterStarTW/LlmBrains.git"
            } else {
                "https://github.com/ShutterStarTW/LlmBrains"
            }
            GitProjectInfo(path, remote, "main")
        }

        val ssh = resolver.resolve(raw(first.toString(), "one"))
        val https = resolver.resolve(raw(second.toString(), "two"))

        assertEquals("github.com/ShutterStarTW/LlmBrains", ssh.gitRemote)
        assertEquals(ssh.id, https.id)
    }

    @Test
    fun `credentials and query parameters are removed from remotes`() {
        val normalized = ProjectResolver.normalizeGitRemote(
            "https://user:secret@example.com/acme/repository.git?token=also-secret#fragment",
        )

        assertEquals("example.com/acme/repository", normalized)
        assertFalse(normalized.orEmpty().contains("secret"))
        assertFalse(normalized.orEmpty().contains("token"))
    }

    @Test
    fun `same directory name at different paths remains distinct without Git identity`() {
        val first = Files.createDirectories(tempDirectory.resolve("one/project"))
        val second = Files.createDirectories(tempDirectory.resolve("two/project"))
        val resolver = ProjectResolver { null }

        assertNotEquals(
            resolver.resolve(raw(first.toString(), "one")).id,
            resolver.resolve(raw(second.toString(), "two")).id,
        )
    }

    @Test
    fun `missing paths receive deterministic session scoped identities`() {
        val resolver = ProjectResolver { null }

        val first = resolver.resolve(raw(null, "one"))
        val repeated = resolver.resolve(raw(null, "one"))
        val second = resolver.resolve(raw(null, "two"))

        assertEquals(first.id, repeated.id)
        assertNotEquals(first.id, second.id)
        assertNull(first.canonicalPath)
    }

    @Test
    fun `Git lookup is cached per normalized path`() {
        val project = Files.createDirectories(tempDirectory.resolve("cached"))
        val calls = AtomicInteger()
        val resolver = ProjectResolver { path ->
            calls.incrementAndGet()
            GitProjectInfo(path, null, null)
        }

        repeat(1_000) { resolver.resolve(raw(project.toString(), "session-$it")) }

        assertEquals(1, calls.get())
    }

    @Test
    fun `Git resolver uses argument based commands and reads optional metadata`() {
        val commands = mutableListOf<List<String>>()
        val resolver = GitProjectResolver { command, _ ->
            commands += command
            when (command.takeLast(2)) {
                listOf("rev-parse", "--show-toplevel") -> "/work/repository"
                listOf("get-url", "origin") -> "git@example.com:team/repository.git"
                listOf("--abbrev-ref", "HEAD") -> "feature/discovery"
                else -> null
            }
        }

        val result = resolver.resolve("/work/repository with spaces")

        assertEquals("/work/repository", result?.root)
        assertEquals("feature/discovery", result?.currentBranch)
        assertTrue(commands.all { it.take(3) == listOf("git", "-C", it[2]) })
        assertEquals("/work/repository with spaces", commands.first()[2])
    }

    @Test
    fun `Git resolver stops when directory is not a repository`() {
        val calls = AtomicInteger()
        val resolver = GitProjectResolver { _, _ ->
            calls.incrementAndGet()
            null
        }

        assertNull(resolver.resolve("/not-a-repository"))
        assertEquals(1, calls.get())
    }

    private fun raw(path: String?, sessionId: String) = RawAgentProject(
        agentId = "codex",
        rawProjectPath = path,
        sessionId = sessionId,
        startedAt = null,
        updatedAt = null,
        sourcePath = null,
    )
}
