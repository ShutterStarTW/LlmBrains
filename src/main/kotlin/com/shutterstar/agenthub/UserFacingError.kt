package com.shutterstar.agenthub

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.logging.Level
import java.util.logging.Logger

/** Gives users an actionable, safe summary while retaining the original failure in the IDE log. */
internal object UserFacingError {
    private val LOG = Logger.getLogger(UserFacingError::class.java.name)

    fun describe(action: String, error: Throwable): String {
        LOG.log(Level.WARNING, action, error)
        val cause = unwrap(error)
        val reason = when (cause) {
            is AccessDeniedException, is SecurityException -> "Access was denied. Check file permissions"
            is NoSuchFileException -> "A required file is missing. Refresh and try again"
            is TimeoutException -> "The operation timed out. Try again"
            is CancellationException, is InterruptedException -> "The operation was interrupted. Try again"
            is com.shutterstar.agenthub.storage.SharedStorageException -> cause.message.orEmpty().trimEnd('.')
            is IOException -> "A file could not be read or written. Check that it is available"
            else -> "An unexpected error occurred. Try again"
        }
        return "$action: $reason. See the IDE log for details."
    }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = current.cause!!
        }
        return current
    }
}
