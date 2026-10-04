package com.shutterstar.agenthub.environment.skills.discovery

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal class SkillFingerprint(
    private val maximumBytes: Long = DEFAULT_MAXIMUM_BYTES,
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
) {
    /**
     * Fingerprints a complete skill directory, including relative paths, file contents and symlink
     * destinations. Arbitrary links are never followed. A regular file is also accepted for the
     * metadata parser's small standalone tests, but synchronization always passes the directory.
     */
    fun calculate(path: Path): String? = runCatching {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return@runCatching calculateFile(path)
        }
        val rootAttributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        // A managed skill may itself be a symlink or Windows junction to the canonical source.
        // Resolve that one reviewed root link, but reject every nested reparse point below.
        val skillRoot = if (rootAttributes.isSymbolicLink || rootAttributes.isOther) path.toRealPath() else path
        if (!Files.isDirectory(skillRoot, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(skillRoot.resolve(SKILL_FILE_NAME), LinkOption.NOFOLLOW_LINKS)
        ) {
            return@runCatching null
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val entries = mutableListOf<Path>()
        Files.walkFileTree(
            skillRoot,
            emptySet(),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    check(!attributes.isOther) { "Skill contains a directory reparse point: $directory" }
                    if (directory != skillRoot && directory.fileName?.toString() in IGNORED_DIRECTORIES) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    if (directory != skillRoot) entries.add(directory)
                    check(entries.size <= maximumEntries) { "Skill contains too many entries" }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    check(!attributes.isOther || attributes.isSymbolicLink) {
                        "Skill contains an unsupported filesystem reparse point: $file"
                    }
                    if (file.fileName?.toString() !in IGNORED_FILES) entries.add(file)
                    check(entries.size <= maximumEntries) { "Skill contains too many entries" }
                    return FileVisitResult.CONTINUE
                }
            },
        )

        var remaining = maximumBytes
        for (entry in entries.sortedBy { skillRoot.relativize(it).toString().replace('\\', '/') }) {
            val relativePath = skillRoot.relativize(entry).toString().replace('\\', '/')
            updateWithString(digest, relativePath)
            when {
                Files.isSymbolicLink(entry) -> {
                    digest.update(TYPE_LINK)
                    updateWithString(digest, Files.readSymbolicLink(entry).toString())
                }
                Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) -> digest.update(TYPE_DIRECTORY)
                Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) -> {
                    digest.update(TYPE_FILE)
                    val size = Files.size(entry)
                    check(size <= remaining) { "Skill content exceeds fingerprint size limit" }
                    updateWithLong(digest, size)
                    Files.newInputStream(entry).use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead < 0) break
                            digest.update(buffer, 0, bytesRead)
                        }
                    }
                    remaining -= size
                }
                else -> error("Unsupported skill entry: $entry")
            }
        }
        digest.digest().toHexString()
    }.getOrNull()

    private fun calculateFile(file: Path): String {
        val size = Files.size(file)
        check(size <= maximumBytes) { "File exceeds fingerprint size limit" }
        val digest = MessageDigest.getInstance("SHA-256")
        updateWithLong(digest, size)
        val buffer = ByteArray(BUFFER_SIZE)
        Files.newInputStream(file).use { input ->
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead < 0) break
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().toHexString()
    }

    private fun updateWithString(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        updateWithLong(digest, bytes.size.toLong())
        digest.update(bytes)
    }

    private fun updateWithLong(digest: MessageDigest, value: Long) {
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
    }

    private fun ByteArray.toHexString(): String = joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
        const val DEFAULT_MAXIMUM_BYTES = 512L * 1024L * 1024L
        const val DEFAULT_MAXIMUM_ENTRIES = 20_000
        const val SKILL_FILE_NAME = "SKILL.md"
        const val TYPE_DIRECTORY: Byte = 1
        const val TYPE_FILE: Byte = 2
        const val TYPE_LINK: Byte = 3
        val IGNORED_DIRECTORIES = setOf(".git", ".idea", ".agenthub")
        val IGNORED_FILES = setOf(".DS_Store", "Thumbs.db")
    }
}
