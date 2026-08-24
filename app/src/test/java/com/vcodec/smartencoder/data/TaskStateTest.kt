package com.vcodec.smartencoder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TaskStateTest {

    @Test
    fun testTaskInitialState() {
        val task = TranscodeTask(
            sourceUri = "content://media/external/video/media/100",
            sourcePath = "DCIM/Camera/",
            destUri = null,
            destPath = null,
            fileName = "VID_2026.mp4",
            originalSize = 100_000_000L
        )

        assertEquals(TaskStatus.PENDING, task.status)
        assertEquals(0.0f, task.progress, 0.001f)
        assertEquals(0L, task.compressedSize)
        assertNull(task.destUri)
        assertNull(task.errorMessage)
    }

    @Test
    fun testTaskAnalyzingAndProcessingTransitions() {
        val task = TranscodeTask(
            sourceUri = "content://media/external/video/media/100",
            sourcePath = "DCIM/Camera/",
            destUri = null,
            destPath = null,
            fileName = "VID_2026.mp4",
            originalSize = 100_000_000L
        )

        val analyzing = task.copy(status = TaskStatus.ANALYZING)
        assertEquals(TaskStatus.ANALYZING, analyzing.status)

        val processing = analyzing.copy(
            status = TaskStatus.PROCESSING,
            startedTimestamp = System.currentTimeMillis(),
            progress = 0.5f,
            targetBitrate = 4_000_000
        )
        assertEquals(TaskStatus.PROCESSING, processing.status)
        assertEquals(0.5f, processing.progress, 0.001f)
        assertEquals(4_000_000, processing.targetBitrate)
    }

    @Test
    fun testTaskCompletionTransition() {
        val task = TranscodeTask(
            sourceUri = "content://media/external/video/media/100",
            sourcePath = "DCIM/Camera/",
            destUri = null,
            destPath = null,
            fileName = "VID_2026.mp4",
            originalSize = 100_000_000L,
            status = TaskStatus.PROCESSING
        )

        val completed = task.copy(
            status = TaskStatus.COMPLETED,
            progress = 1.0f,
            compressedSize = 40_000_000L,
            destUri = "content://media/external/video/media/101",
            finishedTimestamp = System.currentTimeMillis()
        )

        assertEquals(TaskStatus.COMPLETED, completed.status)
        assertEquals(1.0f, completed.progress, 0.001f)
        assertEquals(40_000_000L, completed.compressedSize)
        assertNotNull(completed.destUri)
    }

    @Test
    fun testTaskFailureTransition() {
        val task = TranscodeTask(
            sourceUri = "content://media/external/video/media/100",
            sourcePath = "DCIM/Camera/",
            destUri = null,
            destPath = null,
            fileName = "VID_2026.mp4",
            originalSize = 100_000_000L,
            status = TaskStatus.PROCESSING
        )

        val failed = task.copy(
            status = TaskStatus.FAILED,
            errorMessage = "Encoder init failed",
            finishedTimestamp = System.currentTimeMillis()
        )

        assertEquals(TaskStatus.FAILED, failed.status)
        assertEquals("Encoder init failed", failed.errorMessage)
    }
}
