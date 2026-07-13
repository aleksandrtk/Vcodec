package com.vcodec.smartencoder.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.vcodec.smartencoder.metadata.MetadataRestorer

object MediaStorageManager {

    /**
     * Creates a new pending MediaStore entry or returns the original URI for replacing.
     */
    fun createOutputUri(
        context: Context,
        sourceUri: Uri,
        keepOriginal: Boolean,
        fileName: String,
        originalDates: MetadataRestorer.FileDates?,
        exactName: Boolean = false,
        sourcePath: String? = null
    ): Uri? {
        if (!keepOriginal && !exactName) {
            return sourceUri
        }

        val baseName = fileName.substringBeforeLast(".")
        val ext = fileName.substringAfterLast(".")
        val targetName = if (exactName) fileName else "${baseName}_compressed.$ext"

        val rawPath = if (!sourcePath.isNullOrEmpty()) sourcePath else {
            MetadataRestorer.extractRelativePathFromMediaStore(context, sourceUri) ?: "Movies/SmartEncoder"
        }
        val allowedDirectories = listOf("Movies", "DCIM", "Pictures", "Download")
        val cleanPath = rawPath.trim('/')
        val primaryDir = cleanPath.substringBefore('/')

        var originalRelativePath = if (primaryDir.isEmpty() || !allowedDirectories.any { it.equals(primaryDir, ignoreCase = true) }) {
            if (cleanPath.contains('/')) {
                val restOfPath = cleanPath.substringAfter('/')
                val nextPrimary = restOfPath.substringBefore('/')
                if (allowedDirectories.any { it.equals(nextPrimary, ignoreCase = true) }) {
                    restOfPath
                } else {
                    "Movies/SmartEncoder"
                }
            } else {
                "Movies/SmartEncoder"
            }
        } else {
            cleanPath
        }

        if (!originalRelativePath.endsWith("/")) {
            originalRelativePath += "/"
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, targetName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")

            if (originalDates != null) {
                if (originalDates.dateTakenMs > 0) {
                    put(MediaStore.Video.Media.DATE_TAKEN, originalDates.dateTakenMs)
                }
                if (originalDates.dateModifiedSec > 0) {
                    put(MediaStore.Video.Media.DATE_MODIFIED, originalDates.dateModifiedSec)
                }
                if (originalDates.dateAddedSec > 0) {
                    put(MediaStore.Video.Media.DATE_ADDED, originalDates.dateAddedSec)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, originalRelativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
    }

    /**
     * Completes a pending MediaStore insertion (Android 10+).
     */
    fun finalizePendingUri(context: Context, uri: Uri, originalDates: MetadataRestorer.FileDates?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val updateValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
                if (originalDates != null) {
                    put(MediaStore.Video.Media.DATE_MODIFIED, originalDates.dateModifiedSec)
                }
            }
            context.contentResolver.update(uri, updateValues, null, null)
        }
    }

    /**
     * Retrieves the exact physical size of the content at the given URI.
     */
    fun getUriSize(context: Context, uri: Uri): Long {
        return try {
            val doc = DocumentFile.fromSingleUri(context, uri)
            doc?.length() ?: context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
