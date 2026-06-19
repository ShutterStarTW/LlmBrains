package com.shutterstar.agenthub

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OsDetectorTest {

    @Test
    fun `currentOs is not null`() {
        assertNotNull(OsDetector.currentOs)
    }

    @Test
    fun `isWindows matches currentOs enum`() {
        assertEquals(OsDetector.isWindows(), OsDetector.currentOs == OsDetector.OsType.WINDOWS)
    }

    @Test
    fun `isMac matches currentOs enum`() {
        assertEquals(OsDetector.isMac(), OsDetector.currentOs == OsDetector.OsType.MAC)
    }

    @Test
    fun `at most one OS flag is true`() {
        val active = listOf(OsDetector.isWindows(), OsDetector.isMac()).count { it }
        assertTrue(active <= 1, "multiple OS flags are true simultaneously")
    }
}