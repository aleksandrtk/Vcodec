package com.vcodec.smartencoder.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PathSanitizationTest {

    @Test
    fun testNullOrEmptyPathDefaultsToMoviesSmartEncoder() {
        assertEquals("Movies/SmartEncoder/", MediaStorageManager.sanitizeRelativePath(null))
        assertEquals("Movies/SmartEncoder/", MediaStorageManager.sanitizeRelativePath(""))
        assertEquals("Movies/SmartEncoder/", MediaStorageManager.sanitizeRelativePath("   "))
    }

    @Test
    fun testStandardAllowedDirectories() {
        assertEquals("DCIM/Camera/", MediaStorageManager.sanitizeRelativePath("DCIM/Camera"))
        assertEquals("DCIM/Camera/", MediaStorageManager.sanitizeRelativePath("/DCIM/Camera/"))
        assertEquals("Pictures/Screenshots/", MediaStorageManager.sanitizeRelativePath("Pictures/Screenshots"))
        assertEquals("Movies/Vcodec/", MediaStorageManager.sanitizeRelativePath("Movies/Vcodec"))
        assertEquals("Download/Telegram/", MediaStorageManager.sanitizeRelativePath("Download/Telegram"))
    }

    @Test
    fun testNestedAllowedDirectoryRecovery() {
        // When URI includes app prefix before standard MediaStore root
        assertEquals("Movies/Telegram/", MediaStorageManager.sanitizeRelativePath("external/files/Movies/Telegram"))
        assertEquals("DCIM/Camera/", MediaStorageManager.sanitizeRelativePath("storage/emulated/0/DCIM/Camera"))
        assertEquals("Download/Vids/", MediaStorageManager.sanitizeRelativePath("some/random/Download/Vids"))
    }

    @Test
    fun testUnrecognizedRootDefaultsToSafeDirectory() {
        assertEquals("Movies/SmartEncoder/", MediaStorageManager.sanitizeRelativePath("InvalidRoot/Videos"))
        assertEquals("Movies/SmartEncoder/", MediaStorageManager.sanitizeRelativePath("root/data/system"))
    }

    @Test
    fun testTrailingSlashAlwaysEnforced() {
        val path1 = MediaStorageManager.sanitizeRelativePath("DCIM")
        val path2 = MediaStorageManager.sanitizeRelativePath("DCIM/")
        assertEquals("DCIM/", path1)
        assertEquals("DCIM/", path2)
    }
}
