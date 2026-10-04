package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.util.concurrent.CompletionException

class UserFacingErrorTest {
    @Test
    fun `reports a useful reason without exposing exception text`() {
        val message = UserFacingError.describe(
            "Could not restore backup",
            CompletionException(AccessDeniedException("C:/private/token.txt")),
        )

        assertTrue(message.contains("Check file permissions"))
        assertTrue(message.contains("IDE log"))
        assertFalse(message.contains("token.txt"))
        assertFalse(message.contains("AccessDeniedException"))
    }

    @Test
    fun `gives a retry path for an unexpected error`() {
        val message = UserFacingError.describe("Could not load history", IOException("private connection value"))

        assertTrue(message.contains("Check that it is available"))
        assertFalse(message.contains("private connection value"))
    }
}
