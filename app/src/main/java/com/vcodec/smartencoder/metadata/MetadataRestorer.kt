package com.vcodec.smartencoder.metadata

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log

object MetadataRestorer {
    private const val TAG = "MetadataRestorer"

    init {
        try {
            System.loadLibrary("metadata-restorer")
            Log.i(TAG, "Native library 'metadata-restorer' loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library 'metadata-restorer': ${e.message}")
        }
    }

    /**
     * Native method to parse the source file's MP4 container for metadata boxes (udta, meta, etc.)
     * and inject them into the destination file's MP4 container using file descriptors.
     */
    private external fun copyCustomMetadataBoxesFd(srcFd: Int, destFd: Int, dateTakenMs: Long, dateModifiedSec: Long): Boolean

    /**
     * Native method to directly modify the file's st_mtime and st_atime using futimens.
     * Bypasses MediaStore logic and acts directly on the physical filesystem.
     */
    private external fun setFileDescriptorDatesFd(fd: Int, modifiedSec: Long, accessedSec: Long): Boolean

    /**
     * Data class holding original file dates in seconds since epoch.
     */
    data class FileDates(
        val dateModifiedSec: Long,
        val dateAddedSec: Long,
        val dateTakenMs: Long
    )

    /**
     * Reads the original file's DATE_MODIFIED, DATE_ADDED, and DATE_TAKEN from MediaStore.
     * Must be called BEFORE replacing/overwriting the original file.
     */
    fun readOriginalDatesFromMediaStore(context: Context, sourceUri: Uri): FileDates? {
        try {
            // Try to find the MediaStore entry for this URI
            val projection = arrayOf(
                MediaStore.Video.VideoColumns.DATE_MODIFIED,
                MediaStore.Video.VideoColumns.DATE_ADDED,
                MediaStore.Video.VideoColumns.DATE_TAKEN
            )

            // First try: query MediaStore by _id extracted from URI
            val mediaStoreUri = resolveToMediaStoreUri(context, sourceUri)
            if (mediaStoreUri != null) {
                context.contentResolver.query(mediaStoreUri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DATE_MODIFIED))
                        val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DATE_ADDED))
                        val dateTaken = try {
                            cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DATE_TAKEN))
                        } catch (_: Exception) { 0L }
                        Log.i(TAG, "Read original dates from MediaStore: modified=$dateModified, added=$dateAdded, taken=$dateTaken")
                        return FileDates(dateModified, dateAdded, dateTaken)
                    }
                }
            }

            // Fallback: query all video entries and match by display name
            val displayName = getDisplayName(context, sourceUri)
            if (displayName != null) {
                val selection = "${MediaStore.Video.VideoColumns.DISPLAY_NAME} = ?"
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    arrayOf(displayName),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DATE_MODIFIED))
                        val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DATE_ADDED))
                        val dateTaken = try {
                            cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DATE_TAKEN))
                        } catch (_: Exception) { 0L }
                        Log.i(TAG, "Read original dates from MediaStore (by name fallback): modified=$dateModified, added=$dateAdded, taken=$dateTaken")
                        return FileDates(dateModified, dateAdded, dateTaken)
                    }
                }
            }

            Log.w(TAG, "Could not find MediaStore entry for source URI: $sourceUri")
        } catch (e: Exception) {
            Log.e(TAG, "Exception reading dates from MediaStore: ${e.message}", e)
        }
        return null
    }

    fun extractRelativePathFromMediaStore(context: Context, sourceUri: Uri): String? {
        val projectionsToTry = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            projectionsToTry.add(MediaStore.Video.VideoColumns.RELATIVE_PATH)
        }
        projectionsToTry.add(MediaStore.Video.VideoColumns.DATA)

        // Helper to process cursor result
        fun processCursor(cursor: android.database.Cursor, column: String): String? {
            val idx = cursor.getColumnIndex(column)
            if (idx != -1) {
                val value = cursor.getString(idx)
                if (!value.isNullOrEmpty()) {
                    if (column == MediaStore.Video.VideoColumns.RELATIVE_PATH) {
                        return value
                    } else if (column == MediaStore.Video.VideoColumns.DATA) {
                        // Extract relative path from absolute path (e.g. /storage/emulated/0/DCIM/Camera/vid.mp4)
                        val extDir = android.os.Environment.getExternalStorageDirectory().absolutePath
                        if (value.startsWith(extDir)) {
                            val relative = value.substring(extDir.length).trimStart('/')
                            if (relative.contains("/")) {
                                return relative.substringBeforeLast("/") + "/"
                            }
                        } else if (value.contains("/")) {
                            // Try to guess from general absolute path
                            val parts = value.split("/")
                            if (parts.size >= 3) {
                                // e.g. /storage/1234-5678/DCIM/Camera/vid.mp4 -> DCIM/Camera/
                                val relative = parts.drop(3).dropLast(1).joinToString("/")
                                if (relative.isNotEmpty()) return "$relative/"
                            }
                        }
                    }
                }
            }
            return null
        }

        // 1. Try querying sourceUri directly (best for direct MediaStore or some Pickers)
        for (column in projectionsToTry) {
            try {
                context.contentResolver.query(sourceUri, arrayOf(column), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val path = processCursor(cursor, column)
                        if (path != null) return path
                    }
                }
            } catch (e: Exception) { /* ignore */ }
        }

        // 2. Try resolving to MediaStore URI by Display Name
        try {
            val mediaStoreUri = resolveToMediaStoreUri(context, sourceUri)
            if (mediaStoreUri != null) {
                for (column in projectionsToTry) {
                    context.contentResolver.query(mediaStoreUri, arrayOf(column), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val path = processCursor(cursor, column)
                            if (path != null) return path
                        }
                    }
                }
            }
        } catch (e: Exception) { /* ignore */ }
        
        // 3. Fallback: parse SAF URI path directly
        if (sourceUri.scheme == "content" && sourceUri.authority == "com.android.externalstorage.documents") {
            try {
                val pathStr = sourceUri.path
                if (pathStr != null && pathStr.contains(":")) {
                    val split = pathStr.split(":")
                    if (split.size > 1) {
                        val relativePath = split[1].substringBeforeLast("/")
                        if (relativePath.isNotEmpty()) {
                            return "$relativePath/"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception parsing SAF URI for RELATIVE_PATH: ${e.message}")
            }
        }
        
        return null
    }

    /**
     * Restores the original dates on the destination file in MediaStore.
     * Must be called AFTER MediaScannerConnection.scanFile() has indexed the new file.
     */
    fun restoreMediaStoreDates(context: Context, destUri: Uri, originalDates: FileDates) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.VideoColumns.DATE_MODIFIED, originalDates.dateModifiedSec)
                if (originalDates.dateAddedSec > 0) {
                    put(MediaStore.Video.VideoColumns.DATE_ADDED, originalDates.dateAddedSec)
                }
                if (originalDates.dateTakenMs > 0) {
                    put(MediaStore.Video.VideoColumns.DATE_TAKEN, originalDates.dateTakenMs)
                }
            }

            // Try updating via resolved MediaStore URI
            val mediaStoreUri = resolveToMediaStoreUri(context, destUri)
            if (mediaStoreUri != null) {
                val rows = context.contentResolver.update(mediaStoreUri, values, null, null)
                if (rows > 0) {
                    Log.i(TAG, "Restored dates in MediaStore via direct URI update ($rows rows)")
                    return
                }
            }

            // Fallback: update by display name match
            val displayName = getDisplayName(context, destUri)
            if (displayName != null) {
                restoreMediaStoreDatesByName(context, displayName, originalDates)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception restoring dates in MediaStore: ${e.message}", e)
        }
    }

    /**
     * Retroactively fixes physical file modification dates using JNI and the file descriptor.
     */
    fun fixPhysicalFileDates(context: Context, destUri: Uri, originalDates: FileDates): Boolean {
        var destFd = -1
        try {
            val destPfd = context.contentResolver.openFileDescriptor(destUri, "rw") ?: return false
            destFd = destPfd.detachFd()
            val success = setFileDescriptorDatesFd(destFd, originalDates.dateModifiedSec, originalDates.dateModifiedSec)
            if (success) {
                Log.i(TAG, "Physical file dates successfully updated retroactively via JNI.")
            }
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fix physical file dates retroactively: ${e.message}", e)
            return false
        } finally {
            if (destFd != -1) {
                try { android.system.Os.close(ParcelFileDescriptor.adoptFd(destFd).fileDescriptor) } catch (e: Exception) {}
            }
        }
    }

    /**
     * Forces Android MediaProvider and Samsung Gallery to drop the cached entry and rescan the file
     * by renaming it back and forth using the Storage Access Framework (SAF).
     * This is required because Samsung Gallery ignores silent physical file date changes.
     */
    fun forceGalleryCacheUpdateViaRename(context: Context, destUri: Uri): Uri? {
        try {
            // Get original name
            val originalName = getDisplayName(context, destUri) ?: return null
            val tempName = "temp_${System.currentTimeMillis()}_$originalName"

            // Rename to temp
            val tempUri = android.provider.DocumentsContract.renameDocument(context.contentResolver, destUri, tempName)
            if (tempUri != null) {
                // Rename back to original
                val finalUri = android.provider.DocumentsContract.renameDocument(context.contentResolver, tempUri, originalName)
                if (finalUri != null) {
                    Log.i(TAG, "Successfully performed SAF rename trick to force gallery refresh.")
                    return finalUri
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed SAF rename trick: ${e.message}", e)
        }
        return null
    }

    /**
     * Updates file dates in MediaStore by display name match. 
     * Highly robust for background/retroactive updates where active SAF URIs are unavailable.
     */
    fun restoreMediaStoreDatesByName(context: Context, displayName: String, originalDates: FileDates): Boolean {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.VideoColumns.DATE_MODIFIED, originalDates.dateModifiedSec)
                if (originalDates.dateAddedSec > 0) {
                    put(MediaStore.Video.VideoColumns.DATE_ADDED, originalDates.dateAddedSec)
                }
                if (originalDates.dateTakenMs > 0) {
                    put(MediaStore.Video.VideoColumns.DATE_TAKEN, originalDates.dateTakenMs)
                }
            }
            val selection = "${MediaStore.Video.VideoColumns.DISPLAY_NAME} = ?"
            val rows = context.contentResolver.update(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values,
                selection,
                arrayOf(displayName)
            )
            Log.i(TAG, "Restored dates in MediaStore by name '$displayName' ($rows rows)")
            return rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Exception restoring dates by name: ${e.message}", e)
        }
        return false
    }

    /**
     * Copies all custom container-level metadata.
     */
    fun restoreAllMetadata(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        sourcePath: String?,
        destPath: String?,
        originalDates: FileDates? = null
    ): Boolean {
        var success = false

        // 1. Copy Container Metadata (JNI/NDK via File Descriptors)
        try {
            val srcPfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
            val destPfd = context.contentResolver.openFileDescriptor(destUri, "rw")
            if (srcPfd != null && destPfd != null) {
                var srcFd = -1
                var destFd = -1
                try {
                    srcFd = srcPfd.detachFd() // Detaches to prevent Java from closing it prematurely
                    destFd = destPfd.detachFd()
                    
                    val dateTakenMs = originalDates?.dateTakenMs ?: 0L
                    val dateModifiedSec = originalDates?.dateModifiedSec ?: 0L
                    
                    success = copyCustomMetadataBoxesFd(srcFd, destFd, dateTakenMs, dateModifiedSec)
                    if (success) {
                        Log.i(TAG, "Container metadata copied successfully via native JNI Fd.")
                    } else {
                        Log.e(TAG, "Native JNI Fd container metadata copy failed.")
                    }

                    // 1b. Fix physical file dates via JNI
                    if (originalDates != null) {
                        val datesSuccess = setFileDescriptorDatesFd(destFd, originalDates.dateModifiedSec, originalDates.dateModifiedSec)
                        if (datesSuccess) {
                            Log.i(TAG, "Physical file dates successfully updated via JNI futimens.")
                        } else {
                            Log.e(TAG, "Failed to update physical file dates via JNI.")
                        }
                    }
                } finally {
                    // Close native FDs
                    if (srcFd != -1) {
                        try { android.system.Os.close(ParcelFileDescriptor.adoptFd(srcFd).fileDescriptor) } catch (e: Exception) {}
                    }
                    if (destFd != -1) {
                        try { android.system.Os.close(ParcelFileDescriptor.adoptFd(destFd).fileDescriptor) } catch (e: Exception) {}
                    }
                }
                
                // Fallback: If Android's FUSE filesystem overwrote the mtime upon closing the FD above,
                // try to set the last modified date using the standard Java File API as a secondary fix.
                if (originalDates != null && destPath != null) {
                    try {
                        val file = java.io.File(destPath)
                        if (file.exists()) {
                            file.setLastModified(originalDates.dateModifiedSec * 1000L)
                        }
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during native JNI Fd metadata copy: ${e.message}", e)
        }

        return success
    }

    // --- Helper methods ---

    fun resolveToMediaStoreUri(context: Context, uri: Uri): Uri? {
        // If URI is already a real MediaStore content URI (not a Photo Picker proxy), return it
        val isPickerPath = uri.path?.contains("/picker/") == true
        if (uri.authority == "media" && !isPickerPath) return uri

        // For Photo Picker URIs or other non-MediaStore URIs, resolve by display name
        try {
            val displayName = getDisplayName(context, uri)
            if (displayName != null) {
                val projection = arrayOf(MediaStore.Video.VideoColumns._ID)
                val selection = "${MediaStore.Video.VideoColumns.DISPLAY_NAME} = ?"
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    arrayOf(displayName),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns._ID))
                        return Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve MediaStore URI by display name: ${e.message}")
        }

        // Fallback: try SAF document ID resolution
        try {
            val projection = arrayOf(MediaStore.Video.VideoColumns._ID)
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve MediaStore URI: ${e.message}")
        }
        return null
    }

    private fun getDisplayName(context: Context, uri: Uri): String? {
        try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Reads creation date from the video file container itself using MediaMetadataRetriever.
     * Parses standard formats to return a FileDates object.
     */
    fun extractCreationDateFromVideo(context: Context, videoUri: Uri): FileDates? {
        var retriever: android.media.MediaMetadataRetriever? = null
        try {
            retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, videoUri)
            val dateStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)
            if (!dateStr.isNullOrEmpty()) {
                Log.i(TAG, "Extracted creation date string from video container: $dateStr")
                val parsedMs = parseMetadataDate(dateStr)
                if (parsedMs > 0) {
                    val sec = parsedMs / 1000
                    return FileDates(
                        dateModifiedSec = sec,
                        dateAddedSec = sec,
                        dateTakenMs = parsedMs
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract dates from video file: ${e.message}")
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {}
        }
        return null
    }

    private fun parseMetadataDate(dateStr: String): Long {
        val formats = listOf(
            "yyyyMMdd'T'HHmmss.SSS'Z'",
            "yyyyMMdd'T'HHmmss'Z'",
            "yyyyMMdd'T'HHmmss",
            "yyyyMMdd HHmmss",
            "yyyy-MM-dd HH:mm:ss",
            "EEE MMM dd HH:mm:ss zzz yyyy"
        )
        for (format in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(format, java.util.Locale.US)
                if (format.endsWith("'Z'") || format.contains("zzz")) {
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                val date = sdf.parse(dateStr)
                if (date != null) {
                    return date.time
                }
            } catch (_: Exception) {}
        }
        return 0L
    }

    /**
     * Tries to parse date and time from a file name (e.g. "VID_20221025_153012.mp4").
     * Returns FileDates if successful.
     */
    fun parseDateFromFileName(fileName: String): FileDates? {
        try {
            // Match 8 digits (date) followed by an underscore or dash and then 6 digits (time)
            // e.g. "20240710_220630"
            val regex = Regex("(\\d{4})(\\d{2})(\\d{2})[-_\\s](\\d{2})(\\d{2})(\\d{2})")
            val match = regex.find(fileName)
            if (match != null) {
                val year = match.groups[1]?.value?.toInt() ?: 2024
                val month = (match.groups[2]?.value?.toInt() ?: 1) - 1
                val day = match.groups[3]?.value?.toInt() ?: 1
                val hour = match.groups[4]?.value?.toInt() ?: 0
                val minute = match.groups[5]?.value?.toInt() ?: 0
                val second = match.groups[6]?.value?.toInt() ?: 0

                val calendar = java.util.Calendar.getInstance()
                calendar.set(year, month, day, hour, minute, second)
                val ms = calendar.timeInMillis
                val sec = ms / 1000
                Log.i(TAG, "Parsed date from filename '$fileName': $calendar")
                return FileDates(
                    dateModifiedSec = sec,
                    dateAddedSec = sec,
                    dateTakenMs = ms
                )
            }

            // Match only 8 digits date, e.g. "20240710"
            val dateOnlyRegex = Regex("(\\d{4})[-_\\s]?(\\d{2})[-_\\s]?(\\d{2})")
            val dateMatch = dateOnlyRegex.find(fileName)
            if (dateMatch != null) {
                val year = dateMatch.groups[1]?.value?.toInt() ?: 2024
                val month = (dateMatch.groups[2]?.value?.toInt() ?: 1) - 1
                val day = dateMatch.groups[3]?.value?.toInt() ?: 1

                val calendar = java.util.Calendar.getInstance()
                calendar.set(year, month, day, 12, 0, 0) // Default to noon
                val ms = calendar.timeInMillis
                val sec = ms / 1000
                Log.i(TAG, "Parsed date (only date part) from filename '$fileName': $calendar")
                return FileDates(
                    dateModifiedSec = sec,
                    dateAddedSec = sec,
                    dateTakenMs = ms
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing date from filename '$fileName': ${e.message}")
        }
        return null
    }
}
