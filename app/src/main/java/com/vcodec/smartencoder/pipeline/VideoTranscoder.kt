package com.vcodec.smartencoder.pipeline

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
object VideoTranscoder {
    private const val TAG = "VideoTranscoder"

    interface ProgressListener {
        fun onProgress(progress: Float)
    }

    /**
     * Suspendable function that encodes a video using Media3 Transformer.
     * Yields progress updates via standard listener and handles cancellation.
     */
    suspend fun transcodeVideo(
        context: Context,
        inputUri: Uri,
        outputPath: String,
        targetVideoBitrate: Int,
        targetCodec: String,
        targetWidth: Int,
        targetHeight: Int,
        originalWidth: Int,
        originalHeight: Int,
        isHdr: Boolean,
        forceSdr: Boolean = false,
        listener: ProgressListener
    ): Boolean {
        val deferredResult = CompletableDeferred<Boolean>()

        val videoMimeType = if (targetCodec.equals("H264", ignoreCase = true)) {
            MimeTypes.VIDEO_H264
        } else {
            MimeTypes.VIDEO_H265
        }

        Log.i(TAG, "Starting transcode to $videoMimeType with target bitrate $targetVideoBitrate bps")

        // 1. (Removed TransformationRequest, set directly on Transformer)

        // 2. Build Custom Encoder Factory to inject target video bitrate
        val videoEncoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(targetVideoBitrate)
            .setBitrateMode(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(videoEncoderSettings)
            .build()

        // 3. Initialize Transformer
        val mainHandler = Handler(Looper.getMainLooper())
        var transformer: Transformer? = null

        val transformerListener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                Log.i(TAG, "Transcoding completed successfully.")
                deferredResult.complete(true)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                Log.e(TAG, "Transcoding failed: ${exportException.message}", exportException)
                deferredResult.completeExceptionally(exportException)
            }
        }

        mainHandler.post {
            transformer = Transformer.Builder(context)
                .setEncoderFactory(encoderFactory)
                .setVideoMimeType(videoMimeType)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(transformerListener)
                .build()

            val mediaItem = MediaItem.fromUri(inputUri)

            // 4. Apply resolution scaling effect only if custom target resolution is specified.
            // This prevents OpenGL shader compilation crashes on emulators for "Original" resolution transcode.
            val videoEffects = mutableListOf<Effect>()
            if (targetWidth > 0 && targetHeight > 0 && (targetWidth != originalWidth || targetHeight != originalHeight)) {
                val presentation = Presentation.createForWidthAndHeight(
                    targetWidth,
                    targetHeight,
                    Presentation.LAYOUT_SCALE_TO_FIT
                )
                videoEffects.add(presentation)
            }

            val builder = EditedMediaItem.Builder(mediaItem)
            if (videoEffects.isNotEmpty()) {
                val effects = Effects(emptyList(), videoEffects)
                builder.setEffects(effects)
            }
            val editedMediaItem = builder.build()

            val sequence = EditedMediaItemSequence(listOf(editedMediaItem))
            val compositionBuilder = Composition.Builder(listOf(sequence))

            if (forceSdr) {
                Log.w(TAG, "Forcing HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR for GL fallback.")
                compositionBuilder.setHdrMode(Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR)
            } else if (isHdr) {
                Log.i(TAG, "Preserving HDR using HDR_MODE_KEEP_HDR.")
                compositionBuilder.setHdrMode(Composition.HDR_MODE_KEEP_HDR)
            }

            val composition = compositionBuilder.build()

            try {
                transformer.start(composition, outputPath)
                Log.i(TAG, "Media3 Transformer started on UI thread with Composition.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Media3 Transformer: ${e.message}", e)
                deferredResult.complete(false)
            }
        }

        // 5. Progress Polling Loop
        try {
            val progressHolder = ProgressHolder()
            while (!deferredResult.isCompleted) {
                kotlinx.coroutines.delay(500.milliseconds)
                mainHandler.post {
                    transformer?.let { t ->
                        val state = t.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val progress = progressHolder.progress / 100f
                            listener.onProgress(progress)
                        }
                    }
                }
            }
            return deferredResult.await()
        } catch (e: Exception) {
            Log.w(TAG, "Transcoding was cancelled or encountered an error: ${e.message}")
            mainHandler.post {
                transformer?.cancel()
                Log.i(TAG, "Media3 Transformer cancelled.")
            }
            throw e
        }
    }
}
