package com.vcodec.smartencoder.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vcodec.smartencoder.BuildConfig
import com.vcodec.smartencoder.data.TaskRepository
import com.vcodec.smartencoder.data.TaskStatus
import com.vcodec.smartencoder.data.TranscodeTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MainViewModel"

        /** "Large video" threshold: files above this size are targeted by the large-file mode. */
        const val LARGE_FILE_THRESHOLD_BYTES: Long = 100L * 1024 * 1024

        /**
         * Files with unknown size (0 bytes reported by some gallery providers,
         * e.g. Samsung Gallery ACTION_PICK) always pass the filter — otherwise
         * freshly picked videos would silently disappear from the list.
         */
        fun passesSizeFilter(sizeBytes: Long, onlyLargeFiles: Boolean): Boolean =
            !onlyLargeFiles || sizeBytes <= 0L || sizeBytes > LARGE_FILE_THRESHOLD_BYTES
    }

    private val repository = TaskRepository(application)
    val allTasks: StateFlow<List<TranscodeTask>> = repository.allTasks.stateIn(
        viewModelScope, SharingStarted.Lazily, emptyList()
    )
    val totalSpaceSaved: StateFlow<Long?> = repository.totalSpaceSaved.stateIn(
        viewModelScope, SharingStarted.Lazily, 0L
    )

    enum class SortOrder {
        NAME_ASC,
        NAME_DESC,
        SIZE_ASC,
        SIZE_DESC,
        DATE_ASC,
        DATE_DESC
    }

    private val _sortOrder = MutableStateFlow(SortOrder.DATE_DESC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    private val _scannedFiles = MutableStateFlow<List<ScannedFile>>(emptyList())
    private val _onlyLargeFiles = MutableStateFlow(false)
    val onlyLargeFiles: StateFlow<Boolean> = _onlyLargeFiles.asStateFlow()

    fun setOnlyLargeFiles(enabled: Boolean) {
        _onlyLargeFiles.value = enabled
    }

    val scannedFiles: StateFlow<List<ScannedFile>> = combine(_scannedFiles, _sortOrder, _onlyLargeFiles) { files, order, onlyLarge ->
        val filtered = if (onlyLarge) files.filter { passesSizeFilter(it.size, true) } else files
        when (order) {
            SortOrder.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
            SortOrder.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
            SortOrder.SIZE_ASC -> filtered.sortedBy { it.size }
            SortOrder.SIZE_DESC -> filtered.sortedByDescending { it.size }
            SortOrder.DATE_ASC -> filtered.sortedBy { it.lastModified }
            SortOrder.DATE_DESC -> filtered.sortedByDescending { it.lastModified }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Last folder scan (bucket name + relative path) so Refresh can re-run it. */
    private var lastBucketScan: Pair<String, String>? = null

    private val _selectedFolderUri = MutableStateFlow<Uri?>(null)
    val selectedFolderUri: StateFlow<Uri?> = _selectedFolderUri.asStateFlow()

    private val _selectedFolderName = MutableStateFlow<String?>(null)
    val selectedFolderName: StateFlow<String?> = _selectedFolderName.asStateFlow()

    data class ScannedFile(
        val uri: Uri,
        val path: String?,
        val name: String,
        val size: Long,
        val lastModified: Long = 0L,
        val isSelected: Boolean = false
    )

    /** A folder (MediaStore bucket) that contains videos, shown in the in-app folder picker. */
    data class FolderBucket(
        val name: String,
        val relativePath: String,
        val videoCount: Int,
        val totalSizeBytes: Long
    )

    private val _folderBuckets = MutableStateFlow<List<FolderBucket>>(emptyList())
    val folderBuckets: StateFlow<List<FolderBucket>> = _folderBuckets.asStateFlow()

    private val _isLoadingBuckets = MutableStateFlow(false)
    val isLoadingBuckets: StateFlow<Boolean> = _isLoadingBuckets.asStateFlow()

    /**
     * Lists all on-device folders containing videos with a SINGLE bulk MediaStore query.
     * Replaces the SAF tree picker: no system file manager, no per-directory binder walks —
     * the whole scan is one in-process database query, so it is instant even for huge libraries.
     */
    fun loadFolderBuckets() {
        if (_isLoadingBuckets.value) return
        _isLoadingBuckets.value = true
        viewModelScope.launch {
            val buckets = withContext(Dispatchers.IO) { queryFolderBuckets(getApplication()) }
            _folderBuckets.value = buckets
            _isLoadingBuckets.value = false
        }
    }

    private fun queryFolderBuckets(context: Context): List<FolderBucket> {
        data class Acc(var count: Int = 0, var size: Long = 0L)

        val byPath = LinkedHashMap<String, Acc>()
        try {
            context.contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    android.provider.MediaStore.Video.VideoColumns.BUCKET_DISPLAY_NAME,
                    android.provider.MediaStore.Video.VideoColumns.RELATIVE_PATH,
                    android.provider.MediaStore.Video.VideoColumns.SIZE
                ),
                null, null, null
            )?.use { cursor ->
                val bucketIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.BUCKET_DISPLAY_NAME)
                val pathIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.RELATIVE_PATH)
                val sizeIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.SIZE)
                while (cursor.moveToNext()) {
                    val relPath = if (pathIdx != -1 && !cursor.isNull(pathIdx)) cursor.getString(pathIdx).orEmpty() else ""
                    val bucketName = when {
                        bucketIdx != -1 && !cursor.isNull(bucketIdx) -> cursor.getString(bucketIdx)
                        relPath.isNotBlank() -> relPath.trim('/')
                        else -> "Internal Storage"
                    }
                    val key = "$bucketName|$relPath"
                    val acc = byPath.getOrPut(key) { Acc() }
                    acc.count++
                    if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) acc.size += cursor.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query MediaStore buckets: ${e.message}", e)
        }

        return byPath.mapNotNull { (key, acc) ->
            val name = key.substringBeforeLast('|').ifBlank { "Internal Storage" }
            val relPath = key.substringAfterLast('|')
            FolderBucket(name, relPath, acc.count, acc.size)
        }.sortedWith(compareByDescending<FolderBucket> { it.videoCount }.thenBy { it.name.lowercase() })
    }

    /**
     * Scans one folder (bucket) instantly: a single MediaStore query filtered by
     * BUCKET_DISPLAY_NAME returns every video with id/size/date in ONE database call.
     */
    fun scanBucket(bucketName: String, relativePath: String) {
        lastBucketScan = bucketName to relativePath
        viewModelScope.launch {
            _isScanning.value = true
            _scannedFiles.value = emptyList()

            val list = withContext(Dispatchers.IO) {
                val context = getApplication<Application>()
                val result = mutableListOf<ScannedFile>()
                try {
                    val selection: String
                    val selectionArgs: Array<String>
                    // Match by RELATIVE_PATH when available (precise), otherwise by bucket name
                    if (relativePath.isNotBlank()) {
                        selection = "${android.provider.MediaStore.Video.VideoColumns.RELATIVE_PATH}=?"
                        selectionArgs = arrayOf(relativePath)
                    } else {
                        selection = "${android.provider.MediaStore.Video.VideoColumns.BUCKET_DISPLAY_NAME}=?"
                        selectionArgs = arrayOf(bucketName)
                    }

                    context.contentResolver.query(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(
                            android.provider.MediaStore.Video.VideoColumns._ID,
                            android.provider.MediaStore.Video.VideoColumns.DISPLAY_NAME,
                            android.provider.MediaStore.Video.VideoColumns.SIZE,
                            android.provider.MediaStore.Video.VideoColumns.DATE_MODIFIED,
                            android.provider.MediaStore.Video.VideoColumns.DATE_ADDED
                        ),
                        selection,
                        selectionArgs,
                        "${android.provider.MediaStore.Video.VideoColumns.DATE_MODIFIED} DESC"
                    )?.use { cursor ->
                        val idIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.VideoColumns._ID)
                        val nameIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.SIZE)
                        val modIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.DATE_MODIFIED)
                        val addIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.DATE_ADDED)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idIdx)
                            val name = cursor.getString(nameIdx) ?: continue
                            val size = if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                            val lastModified = when {
                                modIdx != -1 && !cursor.isNull(modIdx) && cursor.getLong(modIdx) > 0 -> cursor.getLong(modIdx) * 1000L
                                addIdx != -1 && !cursor.isNull(addIdx) && cursor.getLong(addIdx) > 0 -> cursor.getLong(addIdx) * 1000L
                                else -> System.currentTimeMillis()
                            }
                            result.add(
                                ScannedFile(
                                    uri = android.content.ContentUris.withAppendedId(
                                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                                    ),
                                    path = relativePath.ifBlank { null },
                                    name = name,
                                    size = size,
                                    lastModified = lastModified,
                                    isSelected = false
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error scanning bucket '$bucketName': ${e.message}", e)
                }
                result.toList()
            }

            _scannedFiles.value = list
            _selectedFolderUri.value = null
            _selectedFolderName.value = "📂 $bucketName (${list.size} videos)"
            _isScanning.value = false
        }
    }

    fun toggleFileSelection(uri: Uri) {
        val current = _scannedFiles.value.map {
            if (it.uri == uri) it.copy(isSelected = !it.isSelected) else it
        }
        _scannedFiles.value = current
    }

    fun toggleAllFilesSelection(selected: Boolean) {
        val onlyLarge = _onlyLargeFiles.value
        val current = _scannedFiles.value.map {
            if (passesSizeFilter(it.size, onlyLarge)) it.copy(isSelected = selected) else it
        }
        _scannedFiles.value = current
    }

    /**
     * Refresh button: re-syncs the scanner list with reality.
     * - If a folder was scanned, the folder scan is simply re-run (picks up new
     *   compressed copies and removes deleted files).
     * - Otherwise (gallery picks), each file is re-validated against MediaStore in ONE
     *   bulk query: sizes/dates are updated and missing (deleted/replaced) files dropped.
     */
    fun refreshScan() {
        val bucket = lastBucketScan
        if (bucket != null) {
            scanBucket(bucket.first, bucket.second)
        } else {
            viewModelScope.launch {
                _isScanning.value = true
                val fresh = withContext(Dispatchers.IO) {
                    revalidateScannedFiles(getApplication(), _scannedFiles.value)
                }
                _scannedFiles.value = fresh
                _isScanning.value = false
            }
        }
    }

    private fun revalidateScannedFiles(context: Context, files: List<ScannedFile>): List<ScannedFile> {
        if (files.isEmpty()) return files

        // Collect MediaStore ids from content URIs
        data class Info(val size: Long, val dateMs: Long)
        val ids = LinkedHashMap<Long, Int>() // id -> index in files
        files.forEachIndexed { idx, f ->
            f.uri.lastPathSegment?.toLongOrNull()?.let { ids[it] = idx }
        }

        val freshInfo = HashMap<Long, Info>()
        try {
            context.contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    android.provider.MediaStore.Video.VideoColumns._ID,
                    android.provider.MediaStore.Video.VideoColumns.SIZE,
                    android.provider.MediaStore.Video.VideoColumns.DATE_MODIFIED
                ),
                "${android.provider.MediaStore.Video.VideoColumns._ID} IN (${ids.keys.joinToString(",")})",
                null, null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.VideoColumns._ID)
                val sizeIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.SIZE)
                val dateIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    freshInfo[cursor.getLong(idIdx)] = Info(
                        size = if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L,
                        dateMs = if (dateIdx != -1 && !cursor.isNull(dateIdx)) cursor.getLong(dateIdx) * 1000L else 0L
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Refresh validation query failed: ${e.message}")
            return files // keep old list on error rather than wiping it
        }

        return files.mapNotNull { file ->
            val info = file.uri.lastPathSegment?.toLongOrNull()?.let { freshInfo[it] }
            when {
                // File no longer exists (deleted / replaced by compression) -> drop it
                ids.containsKey(file.uri.lastPathSegment?.toLongOrNull()) && info == null -> null
                // Still there -> refresh size/date
                info != null -> file.copy(
                    size = info.size.takeIf { it > 0 } ?: file.size,
                    lastModified = info.dateMs.takeIf { it > 0 } ?: file.lastModified
                )
                // Non-MediaStore URI (SAF/picker) — can't validate, keep as-is
                else -> file
            }
        }
    }

    // Transcode parameters state
    private val _targetCodec = MutableStateFlow("HEVC")
    val targetCodec: StateFlow<String> = _targetCodec.asStateFlow()

    private val _targetResolution = MutableStateFlow("Original")
    val targetResolution: StateFlow<String> = _targetResolution.asStateFlow()

    private val _qualityPreset = MutableStateFlow("HIGH_QUALITY")
    val qualityPreset: StateFlow<String> = _qualityPreset.asStateFlow()

    private val _customBitrateMbps = MutableStateFlow(2.0f)
    val customBitrateMbps: StateFlow<Float> = _customBitrateMbps.asStateFlow()

    private val _keepOriginal = MutableStateFlow(true)
    val keepOriginal: StateFlow<Boolean> = _keepOriginal.asStateFlow()

    fun setTargetCodec(codec: String) { _targetCodec.value = codec }
    fun setTargetResolution(res: String) { _targetResolution.value = res }
    fun setQualityPreset(preset: String) { _qualityPreset.value = preset }
    fun setCustomBitrateMbps(bitrate: Float) { _customBitrateMbps.value = bitrate }
    fun setKeepOriginal(keep: Boolean) { _keepOriginal.value = keep }

    fun startQueue() {
        repository.startQueue()
    }

    fun addSelectedToQueue() {
        viewModelScope.launch {
            val onlyLarge = _onlyLargeFiles.value
            val toAdd = _scannedFiles.value.filter { it.isSelected && passesSizeFilter(it.size, onlyLarge) }
            val targetFolder = _selectedFolderUri.value?.toString()

            val codec = _targetCodec.value
            val res = _targetResolution.value
            val preset = _qualityPreset.value
            val keepOrig = _keepOriginal.value

            for (file in toAdd) {
                // Prevent duplicate enqueuing of the same file path/URI
                val alreadyExists = allTasks.value.any { 
                    it.sourceUri == file.uri.toString() && 
                    it.status != TaskStatus.COMPLETED && 
                    it.status != TaskStatus.FAILED 
                }
                if (alreadyExists) continue

                val newTask = TranscodeTask(
                    sourceUri = file.uri.toString(),
                    sourcePath = file.path,
                    destUri = targetFolder,
                    fileName = file.name,
                    originalSize = file.size,
                    status = TaskStatus.PENDING,
                    targetCodec = codec,
                    targetWidth = 0,
                    targetHeight = 0,
                    targetResolution = res,
                    qualityPreset = preset,
                    targetBitrate = if (preset == "CUSTOM") (_customBitrateMbps.value * 1_000_000).toInt() else 0,
                    keepOriginal = keepOrig
                )
                repository.addTask(newTask)
            }

            // Keep the folder selection and scanned list so the user can return from
            // the Queue tab without rescanning; only reset per-file selections.
            _scannedFiles.value = _scannedFiles.value.map { it.copy(isSelected = false) }
        }
    }

    /**
     * Add videos selected from Android document picker (ACTION_OPEN_DOCUMENT).
     * OpenMultipleDocuments returns SAF URIs with read+write access.
     * @param destFolderUri SAF tree URI of the folder where compressed copies will be saved.
     */
    fun addVideosFromPicker(uris: List<Uri>, destFolderUri: Uri? = null) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val context: Context = getApplication()
                val newFiles = mutableListOf<ScannedFile>()

                for (uri in uris) {
                    try {
                        // Take persistable read+write permissions so the URI survives process restarts
                        val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    } catch (_: SecurityException) {
                        // Some providers may not support write or persistable permissions
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: SecurityException) {}
                    }

                    var name = "video.mp4"
                    var size = 0L
                    var lastModified = 0L

                    // 1. Query Display Name and Size (guaranteed to succeed on all content URIs)
                    try {
                        context.contentResolver.query(
                            uri,
                            arrayOf(
                                android.provider.OpenableColumns.DISPLAY_NAME, 
                                android.provider.OpenableColumns.SIZE
                            ),
                            null, null, null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                if (nameIdx != -1) {
                                    name = cursor.getString(nameIdx) ?: "video.mp4"
                                }
                                if (sizeIdx != -1) {
                                    size = cursor.getLong(sizeIdx)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to query OpenableColumns: ${e.message}")
                    }

                    // 2. Query Last Modified Date based on URI scheme/authority
                    try {
                        val isDocument = android.provider.DocumentsContract.isDocumentUri(context, uri)
                        if (isDocument) {
                            // Document URI: query last_modified column
                            context.contentResolver.query(
                                uri,
                                arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                                null, null, null
                            )?.use { c ->
                                if (c.moveToFirst()) {
                                    val idx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                                    if (idx != -1 && !c.isNull(idx)) {
                                        lastModified = c.getLong(idx)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to query Document last modified: ${e.message}")
                    }

                    // 3. MediaStore Fallback for Name, Date and Size (if size is 0 or lastModified is 0 or name is video.mp4)
                    if (size == 0L || lastModified == 0L || name == "video.mp4") {
                        try {
                            val mediaStoreUri = if (uri.authority == android.provider.MediaStore.AUTHORITY) {
                                uri
                            } else {
                                com.vcodec.smartencoder.metadata.MetadataRestorer.resolveToMediaStoreUri(context, uri)
                            }

                            if (mediaStoreUri != null) {
                                context.contentResolver.query(
                                    mediaStoreUri,
                                    arrayOf(
                                        android.provider.MediaStore.Video.VideoColumns.DISPLAY_NAME,
                                        android.provider.MediaStore.Video.VideoColumns.SIZE,
                                        android.provider.MediaStore.Video.VideoColumns.DATE_MODIFIED,
                                        android.provider.MediaStore.Video.VideoColumns.DATE_ADDED
                                    ),
                                    null, null, null
                                )?.use { c ->
                                    if (c.moveToFirst()) {
                                        val nameIdx = c.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.DISPLAY_NAME)
                                        val sizeIdx = c.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.SIZE)
                                        val modIdx = c.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.DATE_MODIFIED)
                                        val addIdx = c.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.DATE_ADDED)

                                        if ((name == "video.mp4" || name.isEmpty()) && nameIdx != -1) {
                                            name = c.getString(nameIdx) ?: "video.mp4"
                                        }
                                        if (size == 0L && sizeIdx != -1) {
                                            size = c.getLong(sizeIdx)
                                        }
                                        if (lastModified == 0L) {
                                            val sec = when {
                                                modIdx != -1 && !c.isNull(modIdx) -> c.getLong(modIdx)
                                                addIdx != -1 && !c.isNull(addIdx) -> c.getLong(addIdx)
                                                else -> 0L
                                            }
                                            if (sec > 0L) {
                                                lastModified = sec * 1000L
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to resolve metadata fallback from MediaStore: ${e.message}")
                        }
                    }

                    if (lastModified == 0L) {
                        lastModified = System.currentTimeMillis()
                    }

                    val resolvedRelativePath = try {
                        com.vcodec.smartencoder.metadata.MetadataRestorer.extractRelativePathFromMediaStore(context, uri)
                    } catch (e: Exception) {
                        null
                    }

                    newFiles.add(
                        ScannedFile(
                            uri = uri,
                            path = resolvedRelativePath,
                            name = name,
                            size = size,
                            lastModified = lastModified,
                            isSelected = true // Auto-select picker files
                        )
                    )
                }

                // Respect the large-file filter for gallery picks as well
                val acceptedFiles = newFiles.filter { passesSizeFilter(it.size, _onlyLargeFiles.value) }

                // Append to existing scanned files (user might have mixed folder + picker)
                _scannedFiles.value = _scannedFiles.value + acceptedFiles

                // Store destination folder if provided
                if (destFolderUri != null) {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            destFolderUri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: SecurityException) {}
                    _selectedFolderUri.value = destFolderUri
                    val folderDoc = DocumentFile.fromTreeUri(context, destFolderUri)
                    _selectedFolderName.value = "📂 ${folderDoc?.name ?: "Destination"} (${acceptedFiles.size} videos)"
                } else {
                    _selectedFolderName.value = "Gallery Selection (${acceptedFiles.size} videos)"
                }
            }
        }
    }

    // --- Estimated output size for selected files ---

    /** Lightweight probe result cached per file URI. */
    private data class EstimateProbe(
        val suggestedBitrate: Int,
        val originalBitrate: Int,
        val durationMs: Long
    )

    private val _sizeEstimates = MutableStateFlow<Map<String, Long>>(emptyMap())
    val sizeEstimates: StateFlow<Map<String, Long>> = _sizeEstimates.asStateFlow()

    private val estimateProbeCache = HashMap<String, EstimateProbe?>()

    /**
     * Estimates the compressed size for each selected file using the SAME bitrate
     * formula the transcode worker applies, so the prediction matches the result:
     * estimated bytes = (targetVideoBitrate + 128k audio) / 8 * durationSeconds.
     * Results stream into [sizeEstimates] as probes complete (fast MediaFormat
     * metadata reads, no decoding). Unknown files are simply absent from the map.
     */
    fun requestEstimates(files: List<ScannedFile>) {
        if (files.isEmpty()) {
            _sizeEstimates.value = emptyMap()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val preset = _qualityPreset.value
            val customBps = (_customBitrateMbps.value * 1_000_000).toInt()
            val result = LinkedHashMap<String, Long>()

            for (file in files) {
                val key = file.uri.toString()
                val probe = synchronized(estimateProbeCache) { estimateProbeCache[key] }
                    ?: run {
                        val analyzed = try {
                            com.vcodec.smartencoder.analyzer.VideoAnalyzer.analyze(context, file.uri)?.let {
                                EstimateProbe(it.suggestedBitrate, it.bitRate, it.durationMs)
                            }
                        } catch (_: Exception) {
                            null
                        }
                        synchronized(estimateProbeCache) { estimateProbeCache[key] = analyzed }
                        analyzed
                    }

                if (probe != null) {
                    // Mirror of VideoTranscodeWorker preset logic
                    val targetBitrate = when (preset) {
                        "HIGH_QUALITY" -> (probe.suggestedBitrate * 1.5).toInt()
                            .coerceAtMost((probe.originalBitrate * 0.9).toInt())
                        "MAX_COMPRESSION" -> (probe.suggestedBitrate * 0.6).toInt()
                            .coerceAtLeast(500_000)
                        "CUSTOM" -> if (customBps > 0) customBps else probe.suggestedBitrate
                        else -> probe.suggestedBitrate // SMART
                    }
                    val seconds = probe.durationMs / 1000.0
                    if (seconds > 0) {
                        // Video target + AAC stereo audio (~128 kbps)
                        result[key] = ((targetBitrate + 128_000) / 8.0 * seconds).toLong()
                    }
                }
                _sizeEstimates.value = result.toMap()
            }
        }
    }

    // --- Theme state (persisted) ---
    private val prefs = application.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    private val _isDarkTheme = MutableStateFlow(prefs.getBoolean("dark_theme", true))
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    fun toggleTheme() {
        val next = !_isDarkTheme.value
        _isDarkTheme.value = next
        prefs.edit().putBoolean("dark_theme", next).apply()
    }

    fun pauseTask(taskId: Long) = viewModelScope.launch { repository.pauseTask(taskId) }
    fun resumeTask(taskId: Long) = viewModelScope.launch { repository.resumeTask(taskId) }

    fun deleteTask(taskId: Long) = viewModelScope.launch { repository.deleteTask(taskId) }

    fun fixAllCompletedTasksDates(onResult: (successCount: Int, failedCount: Int) -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val context = getApplication<Application>()
                val completed = allTasks.value.filter { it.status == TaskStatus.COMPLETED }
                var successCount = 0
                var failedCount = 0

                for (task in completed) {
                    try {
                        var resolvedDates: com.vcodec.smartencoder.metadata.MetadataRestorer.FileDates? = null

                        // 1. Try reading from destination video container metadata if accessible
                        val destUriStr = task.destUri
                        if (destUriStr != null) {
                            try {
                                val destUri = Uri.parse(destUriStr)
                                resolvedDates = com.vcodec.smartencoder.metadata.MetadataRestorer.extractCreationDateFromVideo(context, destUri)
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not extract date from destUri: ${e.message}")
                            }
                        }

                        // 2. Try reading from source video container metadata if accessible
                        if (resolvedDates == null) {
                            try {
                                val sourceUri = Uri.parse(task.sourceUri)
                                resolvedDates = com.vcodec.smartencoder.metadata.MetadataRestorer.extractCreationDateFromVideo(context, sourceUri)
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not extract date from sourceUri: ${e.message}")
                            }
                        }

                        // 3. Fallback: Parse date directly from filename (highly reliable for camera/messaging videos)
                        if (resolvedDates == null) {
                            resolvedDates = com.vcodec.smartencoder.metadata.MetadataRestorer.parseDateFromFileName(task.fileName)
                        }

                        // 4. Fallback: Query MediaStore for original file dates (by source URI or name)
                        if (resolvedDates == null) {
                            try {
                                val sourceUri = Uri.parse(task.sourceUri)
                                resolvedDates = com.vcodec.smartencoder.metadata.MetadataRestorer.readOriginalDatesFromMediaStore(context, sourceUri)
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not read original dates from MediaStore: ${e.message}")
                            }
                        }

                        if (resolvedDates != null) {
                            var restored = false

                            // A) Try physical file update first (most reliable for Samsung)
                            var physicalRestored = false
                            if (destUriStr != null) {
                                try {
                                    val destUri = Uri.parse(destUriStr)
                                    if (com.vcodec.smartencoder.metadata.MetadataRestorer.fixPhysicalFileDates(context, destUri, resolvedDates)) {
                                        physicalRestored = true
                                        restored = true
                                        // Trick Samsung Gallery into picking up the new dates
                                        val newUri = com.vcodec.smartencoder.metadata.MetadataRestorer.forceGalleryCacheUpdateViaRename(context, destUri)
                                        if (newUri != null && newUri.toString() != destUriStr) {
                                            repository.updateTask(task.copy(destUri = newUri.toString()))
                                        }
                                    }
                                } catch (_: Exception) {}
                            }

                            // We MUST also update MediaStore regardless of physical file fix,
                            // otherwise the Gallery still reads the cached 'today' date from the MediaStore database.
                            val relativePath = try {
                                com.vcodec.smartencoder.metadata.MetadataRestorer.extractRelativePathFromMediaStore(context, Uri.parse(task.sourceUri))
                            } catch (_: Exception) { null }

                            var mediaStoreRestored = false

                            // B) Try updating the original filename (Replace mode)
                            val originalName = task.fileName
                            if (com.vcodec.smartencoder.metadata.MetadataRestorer.restoreMediaStoreDatesByName(context, originalName, relativePath, resolvedDates)) {
                                mediaStoreRestored = true
                            }

                            // C) Try updating the compressed filename pattern (Save Copy mode)
                            if (!mediaStoreRestored) {
                                val baseName = task.fileName.substringBeforeLast(".")
                                val ext = task.fileName.substringAfterLast(".")
                                val compressedName = "${baseName}_compressed.${ext}"
                                if (com.vcodec.smartencoder.metadata.MetadataRestorer.restoreMediaStoreDatesByName(context, compressedName, relativePath, resolvedDates)) {
                                    mediaStoreRestored = true
                                }
                            }

                            // D) Also fallback to destUri MediaStore update
                            if (!mediaStoreRestored && destUriStr != null) {
                                try {
                                    val destUri = Uri.parse(destUriStr)
                                    com.vcodec.smartencoder.metadata.MetadataRestorer.restoreMediaStoreDates(context, destUri, resolvedDates)
                                    mediaStoreRestored = true
                                } catch (_: Exception) {}
                            }
                            
                            if (physicalRestored || mediaStoreRestored) restored = true

                            if (restored) {
                                successCount++
                            } else {
                                Log.w(TAG, "Tried restoring dates for '${task.fileName}', but MediaStore update returned 0 rows.")
                                failedCount++
                            }
                        } else {
                            Log.w(TAG, "Could not resolve any date metadata or filename pattern for: ${task.fileName}")
                            failedCount++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore dates for task ${task.id}: ${e.message}")
                        failedCount++
                    }
                }
                withContext(Dispatchers.Main) {
                    onResult(successCount, failedCount)
                }
            }
        }
    }

    fun fixSingleTaskDate(taskId: Long, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val context = getApplication<Application>()
                val task = allTasks.value.find { it.id == taskId }
                if (task == null) {
                    withContext(Dispatchers.Main) { onResult(false) }
                    return@withContext
                }

                try {
                    var resolvedDates: com.vcodec.smartencoder.metadata.MetadataRestorer.FileDates? = null

                    // 1. Try reading from destination video container metadata if accessible
                    val destUriStr = task.destUri
                    if (destUriStr != null) {
                        try {
                            val destUri = Uri.parse(destUriStr)
                            resolvedDates = com.vcodec.smartencoder.metadata.MetadataRestorer.extractCreationDateFromVideo(context, destUri)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not extract date from destUri: ${e.message}")
                        }
                    }

                    // 2. Try reading from source video container metadata if accessible
                    if (resolvedDates == null) {
                        try {
                            val sourceUri = Uri.parse(task.sourceUri)
                            resolvedDates = com.vcodec.smartencoder.metadata.MetadataRestorer.extractCreationDateFromVideo(context, sourceUri)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not extract date from sourceUri: ${e.message}")
                        }
                    }

                    // 3. Fallback: Parse date directly from filename
                    if (resolvedDates == null) {
                        resolvedDates = com.vcodec.smartencoder.metadata.MetadataRestorer.parseDateFromFileName(task.fileName)
                    }

                    // 4. Fallback: Query MediaStore for original file dates (by source URI or name)
                    if (resolvedDates == null) {
                        try {
                            val sourceUri = Uri.parse(task.sourceUri)
                            resolvedDates = com.vcodec.smartencoder.metadata.MetadataRestorer.readOriginalDatesFromMediaStore(context, sourceUri)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not read original dates from MediaStore: ${e.message}")
                        }
                    }

                    if (resolvedDates != null) {
                        var restored = false

                        // A) Try physical file update first
                        var physicalRestored = false
                        if (destUriStr != null) {
                            try {
                                val destUri = Uri.parse(destUriStr)
                                if (com.vcodec.smartencoder.metadata.MetadataRestorer.fixPhysicalFileDates(context, destUri, resolvedDates)) {
                                    physicalRestored = true
                                    restored = true
                                    // Trick Samsung Gallery into picking up the new dates
                                    val newUri = com.vcodec.smartencoder.metadata.MetadataRestorer.forceGalleryCacheUpdateViaRename(context, destUri)
                                    if (newUri != null && newUri.toString() != destUriStr) {
                                        repository.updateTask(task.copy(destUri = newUri.toString()))
                                    }
                                }
                            } catch (_: Exception) {}
                        }

                        // We MUST also update MediaStore regardless of physical file fix.
                        val relativePath = try {
                            com.vcodec.smartencoder.metadata.MetadataRestorer.extractRelativePathFromMediaStore(context, Uri.parse(task.sourceUri))
                        } catch (_: Exception) { null }

                        var mediaStoreRestored = false

                        // B) Try updating the original filename (Replace mode)
                        val originalName = task.fileName
                        if (com.vcodec.smartencoder.metadata.MetadataRestorer.restoreMediaStoreDatesByName(context, originalName, relativePath, resolvedDates)) {
                            mediaStoreRestored = true
                        }

                        // C) Try updating the compressed filename pattern (Save Copy mode)
                        if (!mediaStoreRestored) {
                            val baseName = task.fileName.substringBeforeLast(".")
                            val ext = task.fileName.substringAfterLast(".")
                            val compressedName = "${baseName}_compressed.${ext}"
                            if (com.vcodec.smartencoder.metadata.MetadataRestorer.restoreMediaStoreDatesByName(context, compressedName, relativePath, resolvedDates)) {
                                mediaStoreRestored = true
                            }
                        }

                        // D) Also fallback to destUri direct update
                        if (!mediaStoreRestored && destUriStr != null) {
                            try {
                                val destUri = Uri.parse(destUriStr)
                                com.vcodec.smartencoder.metadata.MetadataRestorer.restoreMediaStoreDates(context, destUri, resolvedDates)
                                mediaStoreRestored = true
                            } catch (_: Exception) {}
                        }
                        
                        if (physicalRestored || mediaStoreRestored) restored = true

                        withContext(Dispatchers.Main) {
                            onResult(restored)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            onResult(false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore date for task ${task.id}: ${e.message}")
                    withContext(Dispatchers.Main) {
                        onResult(false)
                    }
                }
            }
        }
    }

    // --- OTA Update State Management ---
    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _updateInfo = MutableStateFlow<com.vcodec.smartencoder.ota.OtaUpdater.UpdateInfo?>(null)
    val updateInfo: StateFlow<com.vcodec.smartencoder.ota.OtaUpdater.UpdateInfo?> = _updateInfo.asStateFlow()

    private val _isDownloadingUpdate = MutableStateFlow(false)
    val isDownloadingUpdate: StateFlow<Boolean> = _isDownloadingUpdate.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0.0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private val _updateError = MutableStateFlow<String?>(null)
    val updateError: StateFlow<String?> = _updateError.asStateFlow()

    fun checkForUpdates(currentVersion: String = BuildConfig.VERSION_NAME, manual: Boolean = true) {
        viewModelScope.launch {
            _isCheckingUpdate.value = true
            _updateError.value = null
            try {
                val info = com.vcodec.smartencoder.ota.OtaUpdater.checkForUpdates(currentVersion)
                _updateInfo.value = info
                if (manual || info.hasUpdate) {
                    _showUpdateDialog.value = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Check for updates error: ${e.message}", e)
                _updateError.value = e.message ?: "Failed to check for updates"
                if (manual) {
                    _showUpdateDialog.value = true
                }
            } finally {
                _isCheckingUpdate.value = false
            }
        }
    }

    fun startDownloadAndInstall(context: Context) {
        val info = _updateInfo.value ?: return
        val downloadUrl = info.downloadUrl
        if (downloadUrl.isNullOrEmpty()) {
            if (!info.releaseHtmlUrl.isNullOrEmpty()) {
                val browserIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(info.releaseHtmlUrl)).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(browserIntent)
            }
            return
        }

        viewModelScope.launch {
            _isDownloadingUpdate.value = true
            _downloadProgress.value = 0.0f
            _updateError.value = null

            val downloadedFile = com.vcodec.smartencoder.ota.OtaUpdater.downloadApk(
                context = context,
                downloadUrl = downloadUrl,
                onProgress = { progress ->
                    _downloadProgress.value = progress
                },
                expectedSha256 = info.expectedSha256,
                expectedSizeBytes = info.expectedSizeBytes
            )

            _isDownloadingUpdate.value = false

            if (downloadedFile != null && downloadedFile.exists()) {
                com.vcodec.smartencoder.ota.OtaUpdater.installApk(context, downloadedFile)
                _showUpdateDialog.value = false
            } else {
                _updateError.value = "Failed to download update APK (integrity verification may have failed)"
            }
        }
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }
}
