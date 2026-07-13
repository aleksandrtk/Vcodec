package com.vcodec.smartencoder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TaskStatus {
    PENDING,
    ANALYZING,
    PROCESSING,
    PAUSED,
    COMPLETED,
    FAILED
}

@Entity(tableName = "transcode_tasks")
data class TranscodeTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUri: String,
    val sourcePath: String?,
    val destUri: String?,
    val destPath: String?,
    val fileName: String,
    val originalSize: Long,
    val compressedSize: Long = 0L,
    val status: TaskStatus = TaskStatus.PENDING,
    val progress: Float = 0.0f,
    val speedFps: Float = 0.0f,
    val cpuTemp: Float = 0.0f,
    val targetBitrate: Int = 0,
    val originalBitrate: Int = 0,
    val originalCodec: String = "",
    val originalWidth: Int = 0,
    val originalHeight: Int = 0,
    val isHdr: Boolean = false,
    val errorMessage: String? = null,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val startedTimestamp: Long = 0L,
    val finishedTimestamp: Long = 0L,
    val targetCodec: String = "HEVC",
    val targetWidth: Int = 0,
    val targetHeight: Int = 0,
    val targetResolution: String = "Original",
    val qualityPreset: String = "SMART",
    val keepOriginal: Boolean = true
)
