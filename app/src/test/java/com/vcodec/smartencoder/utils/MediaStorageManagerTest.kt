package com.vcodec.smartencoder.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStorageManagerTest {

    @Test
    fun testSanitizeRelativePath_directAllowedRoots() {
        assertEquals("DCIM/Camera/", MediaStorageManager.sanitizeRelativePath("DCIM/Camera"))
        assertEquals("DCIM/Camera/", MediaStorageManager.sanitizeRelativePath("DCIM/Camera/"))
        assertEquals("Movies/Clips/", MediaStorageManager.sanitizeRelativePath("Movies/Clips"))
        assertEquals("Pictures/Screenshots/", MediaStorageManager.sanitizeRelativePath("Pictures/Screenshots/"))
        assertEquals("Download/Videos/", MediaStorageManager.sanitizeRelativePath("Download/Videos"))
    }

    @Test
    fun testSanitizeRelativePath_fullAbsolutePath() {
        assertEquals("DCIM/Camera/", MediaStorageManager.sanitizeRelativePath("storage/emulated/0/DCIM/Camera"))
        assertEquals("Movies/SmartEncoder/", MediaStorageManager.sanitizeRelativePath("/storage/emulated/0/Movies/SmartEncoder/"))
    }

    @Test
    fun testSanitizeRelativePath_unknownOrNullFallback() {
        assertEquals("Movies/SmartEncoder/", MediaStorageManager.sanitizeRelativePath(null))
        assertEquals("Movies/SmartEncoder/", MediaStorageManager.sanitizeRelativePath(""))
        assertEquals("Movies/SmartEncoder/", MediaStorageManager.sanitizeRelativePath("custom/unknown/folder"))
    }
}
