package com.vcodec.smartencoder.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val scannedFiles: StateFlow<List<ScannedFile>> = combine(_scannedFiles, _sortOrder) { files, order ->
        when (order) {
            SortOrder.NAME_ASC -> files.sortedBy { it.name.lowercase() }
            SortOrder.NAME_DESC -> files.sortedByDescending { it.name.lowercase() }
            SortOrder.SIZE_ASC -> files.sortedBy { it.size }
            SortOrder.SIZE_DESC -> files.sortedByDescending { it.size }
            SortOrder.DATE_ASC -> files.sortedBy { it.lastModified }
            SortOrder.DATE_DESC -> files.sortedByDescending { it.lastModified }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

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

    fun selectFolder(uri: Uri) {
        _selectedFolderUri.value = uri
        val doc = DocumentFile.fromTreeUri(getApplication(), uri)
        _selectedFolderName.value = doc?.name ?: "Selected Folder"
        scanFolder(uri)
    }

    private fun scanFolder(treeUri: Uri) {
        viewModelScope.launch {
            _isScanning.value = true
            _scannedFiles.value = emptyList()

            withContext(Dispatchers.IO) {
                val list = mutableListOf<ScannedFile>()
                try {
                    val rootId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                    scanDirectoryContract(getApplication(), treeUri, rootId, list)
                } catch (e: Exception) {
                    Log.e(TAG, "Error scanning folder: ${e.message}", e)
                }
            }
            _isScanning.value = false
        }
    }

    private fun scanDirectoryContract(
        context: Context,
        treeUri: Uri,
        documentId: String,
        list: MutableList<ScannedFile>
    ) {
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
            android.provider.DocumentsContract.Document.COLUMN_SIZE,
            android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_SIZE)
                val dateIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                if (idIndex != -1 && nameIndex != -1 && mimeIndex != -1 && sizeIndex != -1) {
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(idIndex)
                        val name = cursor.getString(nameIndex) ?: ""
                        val mimeType = cursor.getString(mimeIndex) ?: ""
                        val size = cursor.getLong(sizeIndex)
                        var lastModified = if (dateIndex != -1) cursor.getLong(dateIndex) else 0L
                        if (lastModified == 0L) {
                            try {
                                val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                                val resolvedUri = com.vcodec.smartencoder.metadata.MetadataRestorer.resolveToMediaStoreUri(context, fileUri)
                                if (resolvedUri != null) {
                                    context.contentResolver.query(
                                        resolvedUri,
                                        arrayOf(
                                            android.provider.MediaStore.Video.VideoColumns.DATE_MODIFIED,
                                            android.provider.MediaStore.Video.VideoColumns.DATE_ADDED
                                        ),
                                        null, null, null
                                    )?.use { c ->
                                        if (c.moveToFirst()) {
                                            val modIdx = c.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.DATE_MODIFIED)
                                            val addIdx = c.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.DATE_ADDED)
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
                            } catch (_: Exception) {}
                        }
                        if (lastModified == 0L) {
                            lastModified = System.currentTimeMillis()
                        }

                        if (mimeType == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                            scanDirectoryContract(context, treeUri, childId, list)
                        } else {
                            val isMp4 = name.endsWith(".mp4", ignoreCase = true) || 
                                        (mimeType.contains("video", ignoreCase = true) && !name.endsWith(".gif", ignoreCase = true))
                            if (isMp4) {
                                val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                                val scannedFile = ScannedFile(
                                    uri = fileUri,
                                    path = null,
                                    name = name,
                                    size = size,
                                    lastModified = lastModified,
                                    isSelected = false
                                )
                                list.add(scannedFile)
                                // Stream scanned files incrementally to the UI
                                _scannedFiles.value = list.toList()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed query for docId $documentId: ${e.message}", e)
        }
    }

    fun toggleFileSelection(uri: Uri) {
        val current = _scannedFiles.value.map {
            if (it.uri == uri) it.copy(isSelected = !it.isSelected) else it
        }
        _scannedFiles.value = current
    }

    fun toggleAllFilesSelection(selected: Boolean) {
        val current = _scannedFiles.value.map { it.copy(isSelected = selected) }
        _scannedFiles.value = current
    }

    // Transcode parameters state
    private val _targetCodec = MutableStateFlow("HEVC")
    val targetCodec: StateFlow<String> = _targetCodec.asStateFlow()

    private val _targetResolution = MutableStateFlow("Original")
    val targetResolution: StateFlow<String> = _targetResolution.asStateFlow()

    private val _qualityPreset = MutableStateFlow("SMART")
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
            val toAdd = _scannedFiles.value.filter { it.isSelected }
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
                    destPath = null,
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

            // Clear folder selections after adding to queue
            _scannedFiles.value = emptyList()
            _selectedFolderUri.value = null
            _selectedFolderName.value = null
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

                // Append to existing scanned files (user might have mixed folder + picker)
                _scannedFiles.value = _scannedFiles.value + newFiles

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
                    _selectedFolderName.value = "📂 ${folderDoc?.name ?: "Destination"} (${newFiles.size} videos)"
                } else {
                    _selectedFolderName.value = "Gallery Selection (${newFiles.size} videos)"
                }
            }
        }
    }

    fun addVideosFromPickerDirectlyToQueue(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val context: Context = getApplication()
                val codec = _targetCodec.value
                val res = _targetResolution.value
                val preset = _qualityPreset.value
                val keepOrig = _keepOriginal.value
                val customBitrate = if (preset == "CUSTOM") (_customBitrateMbps.value * 1_000_000).toInt() else 0

                for (uri in uris) {
                    try {
                        val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    } catch (_: SecurityException) {
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: SecurityException) {}
                    }

                    var name = "video.mp4"
                    var size = 0L
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
                                name = cursor.getString(0) ?: "video.mp4"
                                size = cursor.getLong(1)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to query URI metadata: ${e.message}")
                    }

                    val newTask = TranscodeTask(
                        sourceUri = uri.toString(),
                        sourcePath = null,
                        destUri = null,
                        destPath = null,
                        fileName = name,
                        originalSize = size,
                        status = TaskStatus.PENDING,
                        targetCodec = codec,
                        targetWidth = 0,
                        targetHeight = 0,
                        targetResolution = res,
                        qualityPreset = preset,
                        targetBitrate = customBitrate,
                        keepOriginal = keepOrig
                    )
                    repository.addTask(newTask)
                }
            }
        }
    }

    fun pauseTask(taskId: Long) = viewModelScope.launch { repository.pauseTask(taskId) }

    fun resumeTask(taskId: Long) = viewModelScope.launch { repository.resumeTask(taskId) }

    fun deleteTask(taskId: Long) = viewModelScope.launch { repository.deleteTask(taskId) }

    fun clearCompleted() = viewModelScope.launch { repository.clearCompleted() }

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
}
