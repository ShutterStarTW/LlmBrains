package com.shutterstar.agenthub.projects.resolve

import com.shutterstar.agenthub.SafeFileTree
import com.shutterstar.agenthub.writeFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GitMetadataReaderTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `reads the root, the origin url and the branch from a plain repository`() {
        writeFile(root.resolve("repo/.git/HEAD"), "ref: refs/heads/main\n")
        writeFile(
            root.resolve("repo/.git/config"),
            "[core]\n\trepositoryformatversion = 0\n[remote \"upstream\"]\n\turl = https://example.test/other.git\n" +
                "[remote \"origin\"]\n\turl = https://github.com/team/repo.git\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n",
        )
        val start = Files.createDirectories(root.resolve("repo/src/deep"))

        val metadata = GitMetadataReader.read(start)!!

        assertEquals(root.resolve("repo").toAbsolutePath().normalize(), metadata.root)
        assertEquals("https://github.com/team/repo.git", metadata.remote)
        assertEquals("main", metadata.currentBranch)
    }

    @Test
    fun `a detached head has no branch and a repository without origin has no remote`() {
        writeFile(root.resolve("repo/.git/HEAD"), "0123456789abcdef0123456789abcdef01234567\n")
        writeFile(root.resolve("repo/.git/config"), "[remote \"other\"]\n\turl = https://example.test/x.git\n")

        val metadata = GitMetadataReader.read(root.resolve("repo"))!!

        assertNull(metadata.currentBranch)
        assertNull(metadata.remote)
    }

    @Test
    fun `a linked worktree takes its head from its own directory and the config from the main one`() {
        writeFile(root.resolve("main/.git/config"), "[remote \"origin\"]\n\turl = https://github.com/team/main.git\n")
        writeFile(root.resolve("main/.git/worktrees/wt/HEAD"), "ref: refs/heads/topic\n")
        writeFile(root.resolve("main/.git/worktrees/wt/commondir"), "../..\n")
        writeFile(root.resolve("wt/.git"), "gitdir: ${root.resolve("main/.git/worktrees/wt").toString().replace('\\', '/')}\n")

        val metadata = GitMetadataReader.read(root.resolve("wt"))!!

        assertEquals(root.resolve("wt").toAbsolutePath().normalize(), metadata.root)
        assertEquals("topic", metadata.currentBranch)
        assertEquals("https://github.com/team/main.git", metadata.remote)
    }

    @Test
    fun `a directory outside any repository gives nothing`() {
        assertNull(GitMetadataReader.read(Files.createDirectories(root.resolve("plain/dir"))))
    }

    @Test
    fun `the origin url is read from its own section only`() {
        assertEquals(
            "git@host:team/x.git",
            GitMetadataReader.originUrl("[remote \"fork\"]\n\turl = a\n[ remote \"origin\" ]\n\turl = git@host:team/x.git\n[branch \"main\"]\n\turl = z\n"),
        )
        assertNull(GitMetadataReader.originUrl("[remote \"origin\"]\n\tpushurl = only-push\n"))
        assertNull(GitMetadataReader.originUrl(null))
    }

    @Test
    fun `the tolerant walk lists the same entries as Files walk within the depth`() {
        writeFile(root.resolve("a/b/c/deep.txt"), "x")
        writeFile(root.resolve("a/top.txt"), "x")
        writeFile(root.resolve("a/b/mid.txt"), "x")

        for (depth in 1..4) {
            val expected = Files.walk(root, depth).use { stream -> stream.toList().toSet() }
            val actual = SafeFileTree.walk(root, depth).use { stream -> stream.toList().toSet() }
            assertEquals(expected, actual, "depth $depth")
        }
    }
}
