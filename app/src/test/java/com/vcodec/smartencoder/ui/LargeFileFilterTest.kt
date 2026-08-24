package com.vcodec.smartencoder.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeFileFilterTest {

    private val threshold = MainViewModel.LARGE_FILE_THRESHOLD_BYTES

    @Test
    fun `filter disabled keeps files of any size`() {
        assertTrue(MainViewModel.passesSizeFilter(0L, onlyLargeFiles = false))
        assertTrue(MainViewModel.passesSizeFilter(threshold, onlyLargeFiles = false))
        assertTrue(MainViewModel.passesSizeFilter(Long.MAX_VALUE, onlyLargeFiles = false))
    }

    @Test
    fun `filter enabled excludes files at or below 100 MB`() {
        assertFalse(MainViewModel.passesSizeFilter(99L * 1024 * 1024, onlyLargeFiles = true))
        assertFalse(MainViewModel.passesSizeFilter(threshold, onlyLargeFiles = true))
    }

    @Test
    fun `filter enabled keeps files with unknown size`() {
        // Samsung Gallery ACTION_PICK may report 0 bytes; such picks must survive the filter
        assertTrue(MainViewModel.passesSizeFilter(0L, onlyLargeFiles = true))
    }

    @Test
    fun `filter enabled includes files strictly above 100 MB`() {
        assertTrue(MainViewModel.passesSizeFilter(threshold + 1, onlyLargeFiles = true))
        assertTrue(MainViewModel.passesSizeFilter(4L * 1024 * 1024 * 1024, onlyLargeFiles = true))
    }
}
