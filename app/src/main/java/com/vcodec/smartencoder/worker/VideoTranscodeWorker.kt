package com.vcodec.smartencoder.worker

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
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
import kotlinx.coroutines.isActive
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

            val (targetWidth, targetHeight) = VideoAnalyzer.calculateTargetDimensions(
                origWidth = videoInfo.width,
                origHeight = videoInfo.height,
                rotation = videoInfo.rotation,
                targetResStr = currentTask.targetResolution
            )

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
                try {
                    while (isActive) {
                        val task = taskDao.getTaskById(taskId)
                        if (task == null || 
                            task.status == TaskStatus.PAUSED || 
                            task.status == TaskStatus.FAILED || 
                            task.status == TaskStatus.COMPLETED) {
                            innerJob?.cancel()
                            break
                        }
                        val currentTemp = ThermalMonitor.getCpuTemperature()
                        // Targeted column update: never clobbers concurrent progress writes
                        taskDao.updateCpuTemp(taskId, currentTemp)
                        delay(1000)
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // Normal coroutine cancellation
                }
            }

            try {
                var success = false
                var finalUri: Uri? = null
                var pfd: ParcelFileDescriptor? = null
                val tempFile = File(context.cacheDir, "transcoded_${taskId}.mp4")

                try {
                    if (tempFile.exists()) tempFile.delete()
                    
                    val outputPath: String

                    // Direct File Descriptor writing optimization for keepOriginal mode
                    if (currentTask.keepOriginal) {
                        finalUri = MediaStorageManager.createOutputUri(
                            context = context,
                            sourceUri = sourceUri,
                            keepOriginal = currentTask.keepOriginal,
                            fileName = currentTask.fileName,
                            originalDates = originalDates,
                            sourcePath = currentTask.sourcePath
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
                                        // Targeted column update: never clobbers concurrent cpuTemp writes
                                        taskDao.updateProgress(taskId, progress)
                                        setForeground(TranscodeNotificationController.createForegroundInfo(context, progress, "Compressing ${currentTask?.fileName ?: ""}"))
                                    }
                                }
                            }
                        )
                    }
                    
                    // Close PFD to flush data
                    pfd?.close()
                    pfd = null

                } catch (e: Exception) {
                    try { pfd?.close() } catch (_: Exception) {}
                    pfd = null

                    if (e is kotlinx.coroutines.CancellationException) {
                        Log.i(TAG, "Transcoding cancelled/aborted.")
                        if (tempFile.exists()) tempFile.delete()
                        return@withContext Result.retry()
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
                                            taskDao.updateProgress(taskId, progress)
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
                        // Final chronology assertion (Replace mode): the new MediaStore entry must
                        // carry the original dates so the compressed video stays in place in the
                        // gallery timeline instead of jumping to the top.
                        if (originalDates != null) {
                            MetadataRestorer.restoreMediaStoreDates(context, finalUri, originalDates)
                        }
                            compressedSize = MediaStorageManager.getUriSize(context, finalUri)
                        } else {
                            throw java.io.IOException("Failed to obtain output URI for keep-original mode")
                        }
                    } else {
                        // Two-Phase Transactional Replace.
                        // Order matters for data safety: a new pending MediaStore entry is created
                        // and fully verified BEFORE the original file is deleted, so any failure
                        // leaves the original untouched.
                        
                        // Step 1: Verify transcoded tempFile integrity BEFORE touching anything
                        if (!tempFile.exists() || tempFile.length() <= 0L) {
                            throw java.io.IOException("Transcoded temporary file is missing or empty, aborting replace to prevent data loss")
                        }

                        // Step 2: Copy custom metadata boxes & physical file dates to tempFile
                        MetadataRestorer.restoreAllMetadata(
                            context = context,
                            sourceUri = sourceUri,
                            destUri = android.net.Uri.fromFile(tempFile),
                            sourcePath = currentTask.sourcePath,
                            destPath = tempFile.absolutePath,
                            originalDates = originalDates
                        )

                        // Re-verify temp file after metadata restoration
                        if (!tempFile.exists() || tempFile.length() <= 0L) {
                            throw java.io.IOException("Temp file corrupted after metadata restoration, aborting replace")
                        }

                        // Step 3: Create a new PENDING MediaStore entry first (original still intact).
                        val targetOutputUri = MediaStorageManager.createOutputUri(
                            context = context,
                            sourceUri = sourceUri,
                            keepOriginal = true,
                            fileName = currentTask.fileName,
                            originalDates = originalDates,
                            exactName = false,
                            sourcePath = currentTask.sourcePath
                        ) ?: throw java.io.IOException("Failed to create MediaStore entry")

                        // Step 4: Copy verified tempFile to the pending MediaStore URI.
                        // Returns the exact byte count written — the reliable verification source.
                        var bytesWritten = 0L
                        try {
                            bytesWritten = MediaStorageManager.copyFileToUri(context, tempFile, targetOutputUri)
                            finalUri = targetOutputUri
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to write to targetOutputUri ($targetOutputUri). Attempting recovery fallback...", e)
                            val recoveryUri = MediaStorageManager.createOutputUri(
                                context = context,
                                sourceUri = sourceUri,
                                keepOriginal = true,
                                fileName = currentTask.fileName,
                                originalDates = originalDates,
                                exactName = false,
                                sourcePath = currentTask.sourcePath
                            )
                            if (recoveryUri != null) {
                                try {
                                    bytesWritten = MediaStorageManager.copyFileToUri(context, tempFile, recoveryUri)
                                    finalUri = recoveryUri
                                } catch (copyError: Exception) {
                                    try { context.contentResolver.delete(recoveryUri, null, null) } catch (_: Exception) {}
                                    try { context.contentResolver.delete(targetOutputUri, null, null) } catch (_: Exception) {}
                                    throw copyError
                                }
                            } else {
                                try { context.contentResolver.delete(targetOutputUri, null, null) } catch (_: Exception) {}
                                throw java.io.IOException("Recovery fallback creation failed", e)
                            }
                        }

                        // Step 5: Verify the copy byte-for-byte against the temp file.
                        // Compares the actual written byte count, NOT the MediaStore SIZE column,
                        // which is unreliable for IS_PENDING entries (often 0 on Samsung).
                        val writtenUri = finalUri ?: throw java.io.IOException("Target output URI is null after copy")
                        if (bytesWritten != tempFile.length()) {
                            try { context.contentResolver.delete(writtenUri, null, null) } catch (_: Exception) {}
                            throw java.io.IOException(
                                "Copied file verification failed (expected ${tempFile.length()} bytes, wrote $bytesWritten). Original kept intact."
                            )
                        }

                        // Step 6: Only NOW delete the original source file
                        var deleteSuccess = false
                        try {
                            deleteSuccess = android.provider.DocumentsContract.deleteDocument(context.contentResolver, sourceUri)
                        } catch (e: Exception) {
                            try {
                                val rows = context.contentResolver.delete(sourceUri, null, null)
                                deleteSuccess = rows > 0
                            } catch (e2: Exception) {
                                Log.e(TAG, "Failed to delete original file: ${e2.message}")
                            }
                        }

                        // Step 7: Claim the exact original filename once the original is gone
                        if (deleteSuccess && !currentTask.fileName.equals(MediaStorageManager.getDisplayName(context, writtenUri), ignoreCase = true)) {
                            try {
                                val renameValues = android.content.ContentValues().apply {
                                    put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, currentTask.fileName)
                                }
                                context.contentResolver.update(writtenUri, renameValues, null, null)
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not rename compressed file to exact original name: ${e.message}")
                            }
                        }

                        // Step 8: Restore physical dates & finalize pending entry
                        MetadataRestorer.restoreAllMetadata(
                            context = context,
                            sourceUri = android.net.Uri.fromFile(tempFile),
                            destUri = writtenUri,
                            sourcePath = tempFile.absolutePath,
                            destPath = writtenUri.path,
                            originalDates = originalDates
                        )
                        MediaStorageManager.finalizePendingUri(context, writtenUri, originalDates)

                        // Final chronology assertion: MediaScanner re-syncs DATE_MODIFIED from the
                        // file mtime when IS_PENDING drops, which pushes the file to the top of the
                        // gallery. Re-assert the original dates AFTER finalization (same as the
                        // keep-original branch) so the video stays at its timeline position.
                        if (originalDates != null) {
                            MetadataRestorer.restoreMediaStoreDates(context, writtenUri, originalDates)
                        }

                        compressedSize = bytesWritten
                    }

                    val targetFileUri = finalUri ?: throw java.io.IOException("Target output URI is null after completion")
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

                    if (tempFile.exists()) tempFile.delete()
                    Log.i(TAG, "Completed Task $taskId successfully.")
                    return@withContext Result.success()
                } else {
                    markTaskFailed(taskId, "Transcoding completed with failure code.")
                    return@withContext Result.failure()
                }
            } finally {
                monitorJob.cancel()
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
