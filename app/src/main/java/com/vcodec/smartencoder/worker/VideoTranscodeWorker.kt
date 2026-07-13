package com.vcodec.smartencoder.worker

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.vcodec.smartencoder.analyzer.VideoAnalyzer
import com.vcodec.smartencoder.data.AppDatabase
import com.vcodec.smartencoder.data.TaskStatus
import com.vcodec.smartencoder.data.TranscodeTask
import com.vcodec.smartencoder.metadata.MetadataRestorer
import com.vcodec.smartencoder.pipeline.VideoTranscoder
import com.vcodec.smartencoder.utils.MediaStorageManager
import com.vcodec.smartencoder.utils.ThermalMonitor
import com.vcodec.smartencoder.utils.TranscodeNotificationController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class VideoTranscodeWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "VideoTranscodeWorker"
        private const val THERMAL_THROTTLE_LIMIT = 45.0f // Celsius
    }

    private val db = AppDatabase.getDatabase(context)
    private val taskDao = db.taskDao()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return TranscodeNotificationController.createForegroundInfo(context, 0.0f, "Initializing...")
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set foreground service: ${e.message}", e)
        }

        val temp = ThermalMonitor.getCpuTemperature()
        if (temp >= THERMAL_THROTTLE_LIMIT) {
            Log.w(TAG, "Device is too hot ($temp C). Throttling transcode. Will retry later.")
            return@withContext Result.retry()
        }

        val inputTaskId = inputData.getLong("TASK_ID", -1L)
        
        var currentTask = if (inputTaskId != -1L) {
            taskDao.getTaskById(inputTaskId)
        } else {
            taskDao.getNextPendingTask()
        }

        if (currentTask == null || currentTask.status == TaskStatus.PAUSED) {
            Log.i(TAG, "No valid pending task found.")
            return@withContext Result.success()
        }

        val taskId = currentTask.id
        Log.i(TAG, "Starting processing of Task $taskId: ${currentTask.fileName}")

        try {
            // 1. Mark as ANALYZING
            updateTaskStatus(taskId, TaskStatus.ANALYZING, 0.0f)
            val sourceUri = Uri.parse(currentTask.sourceUri)

            val originalDates = MetadataRestorer.readOriginalDatesFromMediaStore(context, sourceUri)

            val videoInfo = VideoAnalyzer.analyze(context, sourceUri)
            if (videoInfo == null) {
                markTaskFailed(taskId, "Video analysis failed. Invalid file or codec format.")
                return@withContext Result.failure()
            }

            val suggestedBase = videoInfo.suggestedBitrate
            val calculatedTargetBitrate = when (currentTask.qualityPreset) {
                "HIGH_QUALITY" -> (suggestedBase * 1.5).toInt().coerceAtMost((videoInfo.bitRate * 0.9).toInt())
                "MAX_COMPRESSION" -> (suggestedBase * 0.6).toInt().coerceAtLeast(500_000)
                "CUSTOM" -> if (currentTask.targetBitrate > 0) currentTask.targetBitrate else suggestedBase
                else -> suggestedBase // SMART
            }

            val originalBitrate = videoInfo.bitRate
            val originalCodec = videoInfo.mimeType.substringAfterLast("/")
            val rotation = videoInfo.rotation
            val isRotated = rotation == 90 || rotation == 270
            val logicalOrigWidth = if (isRotated) videoInfo.height else videoInfo.width
            val logicalOrigHeight = if (isRotated) videoInfo.width else videoInfo.height
            val isHdr = videoInfo.isHdr

            var targetWidth = 0
            var targetHeight = 0
            val targetResStr = currentTask.targetResolution

            if (logicalOrigWidth > 0 && logicalOrigHeight > 0) {
                val maxDimension = when (targetResStr) {
                    "1080p" -> 1920
                    "720p" -> 1280
                    else -> 0
                }
                if (maxDimension > 0) {
                    if (logicalOrigWidth >= logicalOrigHeight) {
                        if (logicalOrigWidth > maxDimension) {
                            targetWidth = maxDimension
                            targetHeight = (logicalOrigHeight * maxDimension.toDouble() / logicalOrigWidth).toInt()
                        } else {
                            targetWidth = logicalOrigWidth
                            targetHeight = logicalOrigHeight
                        }
                    } else {
                        if (logicalOrigHeight > maxDimension) {
                            targetHeight = maxDimension
                            targetWidth = (logicalOrigWidth * maxDimension.toDouble() / logicalOrigHeight).toInt()
                        } else {
                            targetWidth = logicalOrigWidth
                            targetHeight = logicalOrigHeight
                        }
                    }
                    targetWidth = (targetWidth / 2) * 2
                    targetHeight = (targetHeight / 2) * 2
                } else {
                    targetWidth = logicalOrigWidth
                    targetHeight = logicalOrigHeight
                }
            }

            val updatedTask = currentTask.copy(
                targetBitrate = calculatedTargetBitrate,
                originalBitrate = originalBitrate,
                originalCodec = originalCodec,
                originalWidth = logicalOrigWidth,
                originalHeight = logicalOrigHeight,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                isHdr = isHdr
            )
            taskDao.updateTask(updatedTask)
            currentTask = updatedTask

            // 3. Mark as PROCESSING and set up targets
            updateTaskStatus(taskId, TaskStatus.PROCESSING, 0.0f)
            setForeground(TranscodeNotificationController.createForegroundInfo(context, 0.0f, "Compressing ${currentTask.fileName}..."))

            var innerJob: kotlinx.coroutines.Job? = null
            val monitorJob = launch {
                while (true) {
                    val task = taskDao.getTaskById(taskId)
                    if (task == null || task.status == TaskStatus.PAUSED) {
                        innerJob?.cancel()
                        break
                    }
                    val currentTemp = ThermalMonitor.getCpuTemperature()
                    taskDao.updateTask(task.copy(cpuTemp = currentTemp))
                    delay(1000)
                }
            }

            var success = false
            var finalUri: Uri? = null
            var pfd: ParcelFileDescriptor? = null
            val tempFile = File(context.cacheDir, "transcoded_${taskId}.mp4")

            try {
                if (tempFile.exists()) tempFile.delete()
                
                val outputPath: String

                // Direct File Descriptor writing optimization
                if (currentTask.keepOriginal) {
                    finalUri = MediaStorageManager.createOutputUri(
                        context = context,
                        sourceUri = sourceUri,
                        keepOriginal = currentTask.keepOriginal,
                        fileName = currentTask.fileName,
                        originalDates = originalDates
                    )
                    
                    if (finalUri != null) {
                        pfd = context.contentResolver.openFileDescriptor(finalUri, "rw")
                        if (pfd != null) {
                            outputPath = "/proc/self/fd/${pfd.fd}"
                        } else {
                            outputPath = tempFile.absolutePath
                        }
                    } else {
                        outputPath = tempFile.absolutePath
                    }
                } else {
                    outputPath = tempFile.absolutePath
                }

                kotlinx.coroutines.coroutineScope {
                    innerJob = this.coroutineContext[kotlinx.coroutines.Job]
                    success = VideoTranscoder.transcodeVideo(
                        context = context,
                        inputUri = sourceUri,
                        outputPath = outputPath,
                        targetVideoBitrate = calculatedTargetBitrate,
                        targetCodec = currentTask.targetCodec,
                        targetWidth = currentTask.targetWidth,
                        targetHeight = currentTask.targetHeight,
                        originalWidth = currentTask.originalWidth,
                        originalHeight = currentTask.originalHeight,
                        isHdr = isHdr,
                        forceSdr = false,
                        listener = object : VideoTranscoder.ProgressListener {
                            override fun onProgress(progress: Float) {
                                launch {
                                    val task = taskDao.getTaskById(taskId)
                                    if (task != null) {
                                        taskDao.updateTask(task.copy(progress = progress))
                                    }
                                    setForeground(TranscodeNotificationController.createForegroundInfo(context, progress, "Compressing ${currentTask?.fileName ?: ""}"))
                                }
                            }
                        }
                    )
                }
                
                // Close PFD to flush data
                pfd?.close()

            } catch (e: Exception) {
                pfd?.close()
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.i(TAG, "Transcoding aborted.")
                    tempFile.delete()
                    return@withContext Result.retry() // Ensure it goes back to pending/paused properly based on DB status
                }
                val errorMsg = e.message ?: ""
                val causeMsg = e.cause?.message ?: ""
                val isGlExtError = errorMsg.contains("GL_EXT_YUV_target") || 
                                   causeMsg.contains("GL_EXT_YUV_target") ||
                                   errorMsg.contains("Video frame processing error")

                if (isGlExtError) {
                    Log.w(TAG, "GL_EXT_YUV_target not supported by GPU. Retrying with SDR fallback...")
                    if (tempFile.exists()) tempFile.delete()
                    
                    // Fallback must use tempFile, since pfd is closed or invalid
                    kotlinx.coroutines.coroutineScope {
                        innerJob = this.coroutineContext[kotlinx.coroutines.Job]
                        success = VideoTranscoder.transcodeVideo(
                            context = context,
                            inputUri = sourceUri,
                            outputPath = tempFile.absolutePath,
                            targetVideoBitrate = calculatedTargetBitrate,
                            targetCodec = currentTask.targetCodec,
                            targetWidth = currentTask.targetWidth,
                            targetHeight = currentTask.targetHeight,
                            originalWidth = currentTask.originalWidth,
                            originalHeight = currentTask.originalHeight,
                            isHdr = isHdr,
                            forceSdr = true,
                            listener = object : VideoTranscoder.ProgressListener {
                                override fun onProgress(progress: Float) {
                                    launch {
                                        val task = taskDao.getTaskById(taskId)
                                        if (task != null) {
                                            taskDao.updateTask(task.copy(progress = progress))
                                        }
                                        setForeground(TranscodeNotificationController.createForegroundInfo(context, progress, "Compressing (SDR Fallback) ${currentTask?.fileName ?: ""}"))
                                    }
                                }
                            }
                        )
                    }
                } else {
                    throw e
                }
            }

            monitorJob.cancel()

            if (success) {
                var compressedSize = 0L

                if (currentTask.keepOriginal) {
                    if (finalUri != null) {
                        // If we fell back to temp file due to FD error, copy it now
                        if (tempFile.exists() && tempFile.length() > 0) {
                            context.contentResolver.openOutputStream(finalUri)?.use { outputStream ->
                                FileInputStream(tempFile).use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                        }
                        
                        // Restore Physical POSIX Dates via C++ NDK BEFORE finalizing
                        MetadataRestorer.restoreAllMetadata(
                            context = context,
                            sourceUri = sourceUri,
                            destUri = finalUri,
                            sourcePath = currentTask.sourcePath,
                            destPath = finalUri.path,
                            originalDates = originalDates
                        )

                        MediaStorageManager.finalizePendingUri(context, finalUri, originalDates)
                        compressedSize = MediaStorageManager.getUriSize(context, finalUri)
                    }
                } else {
                    // Replace original file content directly
                    // STRATEGY: Android 11+ prevents updating DATE_ADDED/DATE_MODIFIED for files we don't own.
                    // Overwriting the file in-place triggers FUSE/MediaScanner to reset the dates to "now".
                    // Since we can't update them back, the file jumps to the top of Samsung Gallery.
                    // SOLUTION: Copy metadata to tempFile -> Delete original file -> Insert a new file with the exact same name.
                    // We become the OWNER of the new file, allowing us to perfectly set the dates via finalizePendingUri!
                    
                    if (tempFile.exists() && tempFile.length() > 0) {
                        // 1. Copy custom metadata boxes from original to tempFile BEFORE deleting original
                        MetadataRestorer.restoreAllMetadata(
                            context = context,
                            sourceUri = sourceUri,
                            destUri = android.net.Uri.fromFile(tempFile),
                            sourcePath = currentTask.sourcePath,
                            destPath = tempFile.absolutePath,
                            originalDates = originalDates
                        )
                        
                        // 2. Delete the original file entirely
                        try {
                            android.provider.DocumentsContract.deleteDocument(context.contentResolver, sourceUri)
                        } catch (e: Exception) {
                            try {
                                context.contentResolver.delete(sourceUri, null, null)
                            } catch (e2: Exception) {
                                Log.e(TAG, "Failed to delete original file: ${e2.message}")
                            }
                        }
                        
                        // 3. Create a brand new file with the EXACT same name
                        finalUri = MediaStorageManager.createOutputUri(
                            context = context,
                            sourceUri = sourceUri,
                            keepOriginal = true, // We MUST use true so it uses MediaStore.insert()
                            fileName = currentTask.fileName,
                            originalDates = originalDates,
                            exactName = true     // Prevents appending "_compressed"
                        ) ?: sourceUri
                        
                        // 4. Copy the transcoded content to the new file
                        context.contentResolver.openOutputStream(finalUri)?.use { outputStream ->
                            FileInputStream(tempFile).use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        
                        // 5. Finalize the new file. Because WE created it, update() will SUCCEED and perfectly restore the dates!
                        MediaStorageManager.finalizePendingUri(context, finalUri, originalDates)
                    } else {
                        finalUri = sourceUri
                    }
                    
                    compressedSize = try {
                        val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, finalUri)
                        doc?.length() ?: tempFile.length()
                    } catch (e: Exception) {
                        tempFile.length()
                    }
                }

                val targetFileUri = finalUri!!
                val completedTask = taskDao.getTaskById(taskId)
                if (completedTask != null) {
                    taskDao.updateTask(
                        completedTask.copy(
                            status = TaskStatus.COMPLETED,
                            progress = 1.0f,
                            compressedSize = compressedSize,
                            destUri = targetFileUri.toString(),
                            finishedTimestamp = System.currentTimeMillis()
                        )
                    )
                }

                tempFile.delete()
                Log.i(TAG, "Completed Task $taskId successfully.")
                return@withContext Result.success()
            } else {
                markTaskFailed(taskId, "Transcoding completed with failure code.")
                return@withContext Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during transcoding: ${e.message}", e)
            val errorMsg = e.message ?: ""
            val causeMsg = e.cause?.message ?: ""
            val isEmulator = Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("google_sdk") || Build.MODEL.contains("Emulator")

            val userFriendlyError = if (isEmulator && (errorMsg.contains("VideoDecoder error") || causeMsg.contains("VideoDecoder error")) && errorMsg.contains("video/hevc")) {
                "Декодер эмулятора (c2.goldfish.hevc.decoder) не поддерживает 10-битные HEVC (Main10) видео."
            } else if (errorMsg.contains("VideoDecoder error") || causeMsg.contains("VideoDecoder error")) {
                "Ошибка декодера видео: устройство не поддерживает декодирование этого формата видео-файла (${e.message})."
            } else {
                e.message ?: "Unknown compression exception."
            }

            markTaskFailed(taskId, userFriendlyError)
            return@withContext Result.failure()
        }
    }

    private suspend fun updateTaskStatus(taskId: Long, status: TaskStatus, progress: Float) {
        val task = taskDao.getTaskById(taskId)
        if (task != null) {
            taskDao.updateTask(
                task.copy(
                    status = status,
                    progress = progress,
                    startedTimestamp = if (status == TaskStatus.PROCESSING) System.currentTimeMillis() else task.startedTimestamp
                )
            )
        }
    }

    private suspend fun markTaskFailed(taskId: Long, error: String) {
        val task = taskDao.getTaskById(taskId)
        if (task != null) {
            taskDao.updateTask(
                task.copy(
                    status = TaskStatus.FAILED,
                    errorMessage = error,
                    finishedTimestamp = System.currentTimeMillis()
                )
            )
        }
    }
}
