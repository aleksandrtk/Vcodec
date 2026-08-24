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
     * Sanitizes and validates a relative storage path against allowed MediaStore roots (Android 10+).
     * Recovers the allowed root even when the URI carries app/storage prefixes
     * (e.g. "storage/emulated/0/DCIM/Camera" -> "DCIM/Camera/").
     */
    fun sanitizeRelativePath(rawPath: String?): String {
        if (rawPath.isNullOrBlank()) {
            return "Movies/SmartEncoder/"
        }
        val allowedDirectories = listOf("Movies", "DCIM", "Pictures", "Download")
        val segments = rawPath.trim('/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Find the first allowed MediaStore root anywhere in the path
        val rootIndex = segments.indexOfFirst { segment ->
            allowedDirectories.any { it.equals(segment, ignoreCase = true) }
        }

        val relativePath = if (rootIndex >= 0) {
            segments.drop(rootIndex).joinToString("/")
        } else {
            "Movies/SmartEncoder"
        }

        return if (relativePath.endsWith("/")) relativePath else "$relativePath/"
    }

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
            MetadataRestorer.extractRelativePathFromMediaStore(context, sourceUri)
        }
        val originalRelativePath = sanitizeRelativePath(rawPath)

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
     * Re-asserts all original dates so the file keeps its exact position
     * in the gallery's chronological timeline after compression/replacement.
     */
    fun finalizePendingUri(context: Context, uri: Uri, originalDates: MetadataRestorer.FileDates?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val updateValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
                if (originalDates != null) {
                    // DATE_MODIFIED drives "modified" sorting; must be re-asserted because
                    // MediaStore refreshes it to 'now' whenever the pending entry is written to.
                    put(MediaStore.Video.Media.DATE_MODIFIED, originalDates.dateModifiedSec)
                    // DATE_ADDED + DATE_TAKEN drive the chronological timeline (Samsung Gallery
                    // sorts primarily by DATE_TAKEN). Re-assert them on finalization.
                    if (originalDates.dateAddedSec > 0) {
                        put(MediaStore.Video.Media.DATE_ADDED, originalDates.dateAddedSec)
                    }
                    if (originalDates.dateTakenMs > 0) {
                        put(MediaStore.Video.Media.DATE_TAKEN, originalDates.dateTakenMs)
                    }
                }
            }
            context.contentResolver.update(uri, updateValues, null, null)
        }
    }

    /**
     * Copies a file to a content URI and returns the exact number of bytes written.
     * Byte counting here is the reliable verification source — MediaStore SIZE
     * queries are unreliable for IS_PENDING entries (often 0 or stale on Samsung).
     */
    fun copyFileToUri(context: Context, src: java.io.File, dest: Uri): Long {
        context.contentResolver.openOutputStream(dest)?.use { outputStream ->
            src.inputStream().use { inputStream ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                    total += read
                }
                outputStream.flush()
                return total
            }
        } ?: throw java.io.IOException("Failed to open output stream: $dest")
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

    /**
     * Retrieves the display name of the content at the given URI, or null on failure.
     */
    fun getDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                    if (idx != -1) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
