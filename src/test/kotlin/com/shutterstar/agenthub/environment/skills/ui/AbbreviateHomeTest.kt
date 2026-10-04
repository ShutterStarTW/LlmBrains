package com.shutterstar.agenthub.environment.skills.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AbbreviateHomeTest {
    @Test
    fun `a path under the home directory starts with a tilde and uses forward slashes`() {
        assertEquals("~/.agenthub", abbreviateHome("C:\\Users\\dev\\.agenthub", "C:\\Users\\dev"))
        assertEquals("~/.agenthub", abbreviateHome("/home/dev/.agenthub", "/home/dev/"))
    }

    @Test
    fun `a path outside the home directory or without a home is only normalised`() {
        assertEquals("D:/shared/agenthub", abbreviateHome("D:\\shared\\agenthub", "C:\\Users\\dev"))
        assertEquals("/home/devil/x", abbreviateHome("/home/devil/x", "/home/dev"))
        assertEquals("/opt/agenthub", abbreviateHome("/opt/agenthub", null))
    }
}
