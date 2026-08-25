package com.vcodec.smartencoder.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class MetadataRestorerTest {

    @Test
    fun testParseDateFromFileName_standardFormat() {
        val fileName = "VID_20240710_220630.mp4"
        val dates = MetadataRestorer.parseDateFromFileName(fileName)

        assertNotNull(dates)
        val cal = Calendar.getInstance().apply { timeInMillis = dates!!.dateTakenMs }
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(Calendar.JULY, cal.get(Calendar.MONTH))
        assertEquals(10, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(22, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(6, cal.get(Calendar.MINUTE))
        assertEquals(30, cal.get(Calendar.SECOND))
    }

    @Test
    fun testParseDateFromFileName_dashFormat() {
        val fileName = "2023-12-25-143000.mp4"
        val dates = MetadataRestorer.parseDateFromFileName(fileName)

        assertNotNull(dates)
        val cal = Calendar.getInstance().apply { timeInMillis = dates!!.dateTakenMs }
        assertEquals(2023, cal.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, cal.get(Calendar.MONTH))
        assertEquals(25, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
    }

    @Test
    fun testParseDateFromFileName_dateOnlyFormat() {
        val fileName = "Vacation_20240501.mp4"
        val dates = MetadataRestorer.parseDateFromFileName(fileName)

        assertNotNull(dates)
        val cal = Calendar.getInstance().apply { timeInMillis = dates!!.dateTakenMs }
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(Calendar.MAY, cal.get(Calendar.MONTH))
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, cal.get(Calendar.HOUR_OF_DAY)) // Default to noon
    }

    @Test
    fun testParseDateFromFileName_noDatePresent() {
        val fileName = "my_awesome_recording.mp4"
        val dates = MetadataRestorer.parseDateFromFileName(fileName)

        assertNull(dates)
    }
}
