package com.vcodec.smartencoder.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OtaIntegrityTest {

    // --- parseDigest ---

    @Test
    fun testParseDigest_valid() {
        val hex = "a".repeat(64)
        assertEquals(hex, OtaUpdater.parseDigest("sha256:$hex"))
    }

    @Test
    fun testParseDigest_uppercaseNormalized() {
        val hex = "ABCDEF0123456789".repeat(4)
        assertEquals(hex.lowercase(), OtaUpdater.parseDigest("sha256:$hex"))
    }

    @Test
    fun testParseDigest_invalid() {
        assertNull(OtaUpdater.parseDigest(null))
        assertNull(OtaUpdater.parseDigest(""))
        assertNull(OtaUpdater.parseDigest("sha256:"))
        assertNull(OtaUpdater.parseDigest("md5:" + "a".repeat(32)))
        assertNull(OtaUpdater.parseDigest("sha256:short"))
        assertNull(OtaUpdater.parseDigest("sha256:" + "z".repeat(64)))
    }

    // --- isValidDownloadUrl ---

    @Test
    fun testIsValidDownloadUrl_allowedHosts() {
        assertTrue(OtaUpdater.isValidDownloadUrl("https://github.com/owner/repo/releases/download/v1/app.apk"))
        assertTrue(OtaUpdater.isValidDownloadUrl("https://objects.githubusercontent.com/path/app.apk"))
        assertTrue(OtaUpdater.isValidDownloadUrl("https://release-assets.githubusercontent.com/path/app.apk"))
    }

    @Test
    fun testIsValidDownloadUrl_blockedHostsAndSchemes() {
        assertFalse(OtaUpdater.isValidDownloadUrl("http://github.com/app.apk")) // no https
        assertFalse(OtaUpdater.isValidDownloadUrl("https://evil.example.com/app.apk"))
        assertFalse(OtaUpdater.isValidDownloadUrl("https://github.com.evil.com/app.apk"))
        assertFalse(OtaUpdater.isValidDownloadUrl("not a url"))
        assertFalse(OtaUpdater.isValidDownloadUrl(""))
    }

    // --- computeSha256 ---

    @Test
    fun testComputeSha256_knownVector() {
        val temp = File.createTempFile("sha_test", ".bin")
        try {
            temp.writeBytes(byteArrayOf()) // SHA-256 of empty input
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                OtaUpdater.computeSha256(temp)
            )
        } finally {
            temp.delete()
        }
    }

    @Test
    fun testComputeSha256_missingFileReturnsNull() {
        assertNull(OtaUpdater.computeSha256(File("/nonexistent/path/file.bin")))
    }
}
