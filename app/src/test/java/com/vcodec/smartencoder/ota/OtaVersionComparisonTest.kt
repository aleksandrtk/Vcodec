package com.vcodec.smartencoder.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaVersionComparisonTest {

    @Test
    fun testParseVersionNumbers() {
        assertEquals(listOf(1, 0, 0), OtaUpdater.parseVersionNumbers("v1.0.0"))
        assertEquals(listOf(1, 0, 0), OtaUpdater.parseVersionNumbers("1.0.0"))
        assertEquals(listOf(2, 0), OtaUpdater.parseVersionNumbers("release2.0-stable"))
        assertEquals(listOf(2, 0, 1), OtaUpdater.parseVersionNumbers("Release-v2.0.1-beta2"))
        assertEquals(listOf(0, 1, 0), OtaUpdater.parseVersionNumbers("v0.1.0"))
        assertEquals(listOf(3, 14, 15), OtaUpdater.parseVersionNumbers("3.14.15"))
    }

    @Test
    fun testIsNewerVersion() {
        // Standard semantic versions
        assertTrue("v2.0.0 should be newer than 1.0.0", OtaUpdater.isNewerVersion("1.0.0", "v2.0.0"))
        assertTrue("1.0.1 should be newer than 1.0.0", OtaUpdater.isNewerVersion("1.0.0", "1.0.1"))
        assertTrue("1.1.0 should be newer than 1.0.5", OtaUpdater.isNewerVersion("1.0.5", "1.1.0"))

        // Custom tags like release2.0-stable
        assertTrue("release2.0-stable should be newer than 1.0.0", OtaUpdater.isNewerVersion("1.0.0", "release2.0-stable"))
        assertTrue("release2.0-stable should be newer than v0.1.0", OtaUpdater.isNewerVersion("v0.1.0", "release2.0-stable"))

        // Same or older versions
        assertFalse("1.0.0 is not newer than 1.0.0", OtaUpdater.isNewerVersion("1.0.0", "1.0.0"))
        assertFalse("v1.0.0 is not newer than 1.0.0", OtaUpdater.isNewerVersion("1.0.0", "v1.0.0"))
        assertFalse("1.0.0 is not newer than 2.0.0", OtaUpdater.isNewerVersion("2.0.0", "1.0.0"))
        assertFalse("release1.0-stable is not newer than 2.0.0", OtaUpdater.isNewerVersion("2.0.0", "release1.0-stable"))
    }

    @Test
    fun testEqualLengthPadding() {
        // 2.0 vs 2.0.0 are treated as equal
        assertFalse("2.0 and 2.0.0 are equivalent", OtaUpdater.isNewerVersion("2.0", "2.0.0"))
        assertFalse("2.0.0 and 2.0 are equivalent", OtaUpdater.isNewerVersion("2.0.0", "2.0"))
        assertTrue("2.0.1 is newer than 2.0", OtaUpdater.isNewerVersion("2.0", "2.0.1"))
    }
}
