package com.vcodec.smartencoder.analyzer

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.IOException

object VideoAnalyzer {
    private const val TAG = "VideoAnalyzer"

    data class VideoInfo(
        val mimeType: String,
        val width: Int,
        val height: Int,
        val rotation: Int = 0,
        val frameRate: Int,
        val bitRate: Int,
        val durationMs: Long,
        val isHdr: Boolean,
        val colorStandard: Int,
        val colorTransfer: Int,
        val isHevc: Boolean,
        val isAv1: Boolean,
        val audioMimeType: String?,
        val audioBitRate: Int,
        val suggestedBitrate: Int
    )

    fun analyze(context: Context, uri: Uri): VideoInfo? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackCount = extractor.trackCount
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoFormat = format
                } else if (mime.startsWith("audio/")) {
                    audioFormat = format
                }
            }

            if (videoFormat == null) {
                Log.e(TAG, "No video track found in the media file.")
                return null
            }

            val mimeType = videoFormat.getString(MediaFormat.KEY_MIME) ?: "video/unknown"
            val width = videoFormat.getSafeInteger(MediaFormat.KEY_WIDTH, 1920)
            val height = videoFormat.getSafeInteger(MediaFormat.KEY_HEIGHT, 1080)
            val durationUs = videoFormat.getSafeLong(MediaFormat.KEY_DURATION, 0L)
            val durationMs = durationUs / 1000

            // Frame Rate
            var frameRate = videoFormat.getSafeInteger(MediaFormat.KEY_FRAME_RATE, 30)
            if (frameRate <= 0) frameRate = 30

            // Bit Rate
            var bitRate = videoFormat.getSafeInteger(MediaFormat.KEY_BIT_RATE, 0)
            if (bitRate <= 0 && durationUs > 0) {
                // Estimate bitrate based on file size if metadata key is missing
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        val fileSize = afd.length
                        bitRate = ((fileSize * 8) / (durationUs / 1000000.0)).toInt()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to estimate bitrate from file size: ${e.message}")
                }
            }
            if (bitRate <= 0) bitRate = 15_000_000 // Fallback 15 Mbps

            // HDR detection
            val colorStandard = videoFormat.getSafeInteger(MediaFormat.KEY_COLOR_STANDARD, -1)
            val colorTransfer = videoFormat.getSafeInteger(MediaFormat.KEY_COLOR_TRANSFER, -1)
            val profile = videoFormat.getSafeInteger(MediaFormat.KEY_PROFILE, -1)

            val isHdr = (colorStandard == MediaFormat.COLOR_STANDARD_BT2020 &&
                    (colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                            colorTransfer == MediaFormat.COLOR_TRANSFER_HLG)) ||
                    profile == 2 // HEVC Main10 profile usually corresponds to 10-bit HDR.

            val isHevc = mimeType.contains("hevc") || mimeType.contains("h265")
            val isAv1 = mimeType.contains("av01") || mimeType.contains("av1")

            // Extract video rotation
            val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                videoFormat.getSafeInteger(MediaFormat.KEY_ROTATION, 0)
            } else {
                videoFormat.getSafeInteger("rotation-degrees", 0)
            }

            // Audio settings
            val audioMimeType = audioFormat?.getString(MediaFormat.KEY_MIME)
            val audioBitRate = audioFormat?.getSafeInteger(MediaFormat.KEY_BIT_RATE, 128_000) ?: 128_000

            // Calculate suggested bitrate
            val suggestedBitrate = calculateSuggestedBitrate(width, height, frameRate, isHdr, bitRate, isHevc, isAv1)

            return VideoInfo(
                mimeType = mimeType,
                width = width,
                height = height,
                rotation = rotation,
                frameRate = frameRate,
                bitRate = bitRate,
                durationMs = durationMs,
                isHdr = isHdr,
                colorStandard = colorStandard,
                colorTransfer = colorTransfer,
                isHevc = isHevc,
                isAv1 = isAv1,
                audioMimeType = audioMimeType,
                audioBitRate = audioBitRate,
                suggestedBitrate = suggestedBitrate
            )
        } catch (e: IOException) {
            Log.e(TAG, "Failed to analyze video: ${e.message}", e)
            return null
        } finally {
            extractor.release()
        }
    }

    /**
     * Heuristic for determining optimal bitrate for transcoding to HEVC.
     */
    private fun calculateSuggestedBitrate(
        width: Int,
        height: Int,
        frameRate: Int,
        isHdr: Boolean,
        originalBitrate: Int,
        isHevc: Boolean,
        isAv1: Boolean
    ): Int {
        // Base bitrate targets for SDR H.265 encoding at 30 fps
        val pixels = width * height
        val baseBitrate = when {
            pixels >= 3840 * 2160 -> 12_000_000 // 4K -> Target 12 Mbps
            pixels >= 1920 * 1080 -> 4_000_000  // 1080p -> Target 4 Mbps
            pixels >= 1280 * 720  -> 2_000_000  // 720p -> Target 2 Mbps
            else -> 1_000_000                  // SD -> Target 1 Mbps
        }

        // Adjust for frame rate (increase target for 60fps)
        val fpsFactor = if (frameRate > 40) 1.4 else 1.0

        // Adjust for HDR (require 25% higher bitrate to prevent color/gradient banding)
        val hdrFactor = if (isHdr) 1.25 else 1.0

        var targetBitrate = (baseBitrate * fpsFactor * hdrFactor).toInt()

        // If the original video is already HEVC or AV1 and has a low bitrate,
        // we should compress it even more aggressively (e.g., 60-70% of original),
        // but avoid compression if the size savings would be negligible.
        if (isHevc || isAv1) {
            val discountRate = if (isAv1) 0.6 else 0.75
            val secondaryTarget = (originalBitrate * discountRate).toInt()
            // Take the smaller of our target and the discounted original bitrate
            targetBitrate = minOf(targetBitrate, secondaryTarget)
        } else {
            // For H.264 -> H.265, target a ~50% savings compared to original, but clamp to targetBitrate
            val h264TranscodeTarget = (originalBitrate * 0.5).toInt()
            targetBitrate = minOf(targetBitrate, h264TranscodeTarget)
        }

        // Never suggest a bitrate higher than 85% of original
        val maxSafeBitrate = (originalBitrate * 0.85).toInt()
        targetBitrate = minOf(targetBitrate, maxSafeBitrate)

        // Enforce absolute floors to maintain minimum quality (e.g., 800 Kbps)
        return maxOf(targetBitrate, 800_000)
    }

    private fun MediaFormat.getSafeInteger(key: String, defaultValue: Int): Int {
        return try {
            if (containsKey(key)) getInteger(key) else defaultValue
        } catch (e: Exception) {
            defaultValue
        }
    }

    private fun MediaFormat.getSafeLong(key: String, defaultValue: Long): Long {
        return try {
            if (containsKey(key)) getLong(key) else defaultValue
        } catch (e: Exception) {
            defaultValue
        }
    }
}
