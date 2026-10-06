package com.shutterstar.agenthub

import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Spliterator
import java.util.Spliterators
import java.util.stream.Stream
import java.util.stream.StreamSupport

/**
 * [Files.walk] that survives entries it cannot inspect. Over the `\wsl.localhost` share Windows cannot read the
 * attributes of Linux symlinks (`FileSystemException`), and [Files.walk] turns that into an exception that ends the
 * whole walk; here such an entry is just listed as a plain path and an unreadable directory is skipped.
 *
 * Same contract as [Files.walk] otherwise: lazy, depth-first, [start] first, symbolic links are not followed,
 * and the stream must be closed.
 */
internal object SafeFileTree {
    fun walk(start: Path, maxDepth: Int): Stream<Path> {
        val iterator = Walker(start, maxDepth)
        return StreamSupport
            .stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED or Spliterator.DISTINCT), false)
            .onClose(iterator::close)
    }

    private class Walker(private val start: Path, private val maxDepth: Int) : Iterator<Path> {
        private class Level(val stream: DirectoryStream<Path>, val entries: Iterator<Path>)

        private val levels = ArrayDeque<Level>()
        private var startReturned = false
        private var next: Path? = null

        override fun hasNext(): Boolean {
            if (next == null) next = advance()
            return next != null
        }

        override fun next(): Path {
            val value = next ?: advance() ?: throw NoSuchElementException()
            next = null
            return value
        }

        private fun advance(): Path? {
            if (!startReturned) {
                startReturned = true
                descend(start)
                return start
            }
            while (levels.isNotEmpty()) {
                val level = levels.last()
                val entry = try {
                    if (level.entries.hasNext()) level.entries.next() else null
                } catch (_: Exception) {
                    null
                }
                if (entry == null) {
                    runCatching { level.stream.close() }
                    levels.removeLast()
                    continue
                }
                descend(entry)
                return entry
            }
            return null
        }

        /** Opens [directory] for listing when it is one (not through a link) and the depth allows. */
        private fun descend(directory: Path) {
            if (levels.size >= maxDepth) return
            // isDirectory answers false, not an exception, for entries the share cannot describe.
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return
            try {
                val stream = Files.newDirectoryStream(directory)
                levels.addLast(Level(stream, stream.iterator()))
            } catch (_: IOException) {
                // Unreadable directory: skipped.
            } catch (_: RuntimeException) {
                // DirectoryIteratorException and friends.
            }
        }

        fun close() {
            while (levels.isNotEmpty()) runCatching { levels.removeLast().stream.close() }
        }
    }
}
