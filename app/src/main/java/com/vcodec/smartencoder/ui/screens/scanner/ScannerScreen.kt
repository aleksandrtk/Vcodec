package com.vcodec.smartencoder.ui.screens.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vcodec.smartencoder.metadata.MetadataRestorer
import com.vcodec.smartencoder.ui.MainViewModel
import com.vcodec.smartencoder.ui.components.FolderPickerDialog
import com.vcodec.smartencoder.ui.components.InfoDot
import com.vcodec.smartencoder.ui.components.VideoPreviewDialog
import com.vcodec.smartencoder.ui.theme.AccentEmerald
import com.vcodec.smartencoder.ui.theme.DarkSurface
import com.vcodec.smartencoder.ui.theme.LocalAppColors
import com.vcodec.smartencoder.ui.theme.PrimaryCyan
import com.vcodec.smartencoder.ui.theme.SuccessColor
import com.vcodec.smartencoder.ui.theme.TextGray
import com.vcodec.smartencoder.ui.theme.TextWhite
import java.util.Locale

@Composable
fun ScannerScreen(viewModel: MainViewModel, onNavigateToQueue: () -> Unit) {
    val context = LocalContext.current
    val folderUri by viewModel.selectedFolderUri.collectAsState()
    val folderName by viewModel.selectedFolderName.collectAsState()
    val scannedFiles by viewModel.scannedFiles.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    val targetCodec by viewModel.targetCodec.collectAsState()
    val targetResolution by viewModel.targetResolution.collectAsState()
    val qualityPreset by viewModel.qualityPreset.collectAsState()
    val customBitrateMbps by viewModel.customBitrateMbps.collectAsState()
    val keepOriginal by viewModel.keepOriginal.collectAsState()
    val sizeEstimates by viewModel.sizeEstimates.collectAsState()

    var showFolderPicker by remember { mutableStateOf(false) }
    val folderBuckets by viewModel.folderBuckets.collectAsState()
    val isLoadingBuckets by viewModel.isLoadingBuckets.collectAsState()

    if (showFolderPicker) {
        FolderPickerDialog(
            buckets = folderBuckets,
            isLoading = isLoadingBuckets,
            onDismiss = { showFolderPicker = false },
            onSelect = { bucket ->
                showFolderPicker = false
                viewModel.scanBucket(bucket.name, bucket.relativePath)
            }
        )
    }

    // Returns real writable SAF URIs (not Photo Picker sandbox URIs)
    val pickVideosLauncher = rememberLauncherForActivityResult(
        contract = object : ActivityResultContract<Unit, List<Uri>>() {
            override fun createIntent(context: Context, input: Unit): Intent {
                return Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).apply {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }

            override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
                if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
                val uris = mutableListOf<Uri>()
                intent.data?.let { uris.add(it) }
                intent.clipData?.let { clipData ->
                    for (i in 0 until clipData.itemCount) {
                        clipData.getItemAt(i).uri?.let { uris.add(it) }
                    }
                }
                return uris
            }
        }
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addVideosFromPicker(uris, null)
        }
    }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.addSelectedToQueue()
            onNavigateToQueue()
        } else {
            Toast.makeText(
                context,
                "Write permission denied. Files will be saved as copies instead.",
                Toast.LENGTH_LONG
            ).show()
            viewModel.setKeepOriginal(true)
            viewModel.addSelectedToQueue()
            onNavigateToQueue()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (isScanning) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = PrimaryCyan)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Scanning media files...", color = TextGray)
                }
            }
        } else if (scannedFiles.isEmpty()) {
            // Start screen: Select file or folder
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "Select Source",
                    tint = PrimaryCyan.copy(alpha = 0.8f),
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Welcome to VCodec",
                    color = TextWhite,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Select videos to compress — pick from gallery or scan a folder.",
                    color = TextGray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = { pickVideosLauncher.launch(Unit) },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan),
                    shape = RoundedCornerShape(12.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Gallery", tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pick from Gallery", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
                Text(
                    "Quick selection of specific videos directly from your storage.",
                    color = TextGray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 20.dp)
                )

                OutlinedButton(
                    onClick = {
                        viewModel.loadFolderBuckets()
                        showFolderPicker = true
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, PrimaryCyan.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(imageVector = Icons.Default.Folder, contentDescription = "Folder", tint = PrimaryCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan Entire Folder", color = PrimaryCyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Text(
                    "Scan a whole directory to find and bulk compress older files.",
                    color = TextGray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        } else {
            // File/folder selected
            val selectedCount = scannedFiles.count { it.isSelected }
            val selectedFiles = scannedFiles.filter { it.isSelected }
            val selectedSizeBytes = selectedFiles.sumOf { it.size }
            val estimatedTotalBytes = selectedFiles.sumOf { file -> sizeEstimates[file.uri.toString()] ?: 0L }
            val hasFullEstimate = selectedFiles.isNotEmpty() &&
                selectedFiles.all { sizeEstimates.containsKey(it.uri.toString()) }

            LaunchedEffect(
                selectedFiles.map { it.uri },
                qualityPreset,
                customBitrateMbps,
                targetResolution
            ) {
                viewModel.requestEstimates(selectedFiles)
            }

            var previewFile by remember { mutableStateOf<MainViewModel.ScannedFile?>(null) }
            previewFile?.let { file ->
                VideoPreviewDialog(
                    uri = file.uri,
                    name = file.name,
                    onDismiss = { previewFile = null }
                )
            }
            var sortMenuExpanded by remember { mutableStateOf(false) }
            val sortOrder by viewModel.sortOrder.collectAsState()
            val onlyLargeFiles by viewModel.onlyLargeFiles.collectAsState()
            val rawScannedCount by viewModel.rawScannedCount.collectAsState()

            val onRunClick: () -> Unit = {
                val selectedFilesToRun = scannedFiles.filter { it.isSelected }
                val mediaStoreUris = selectedFilesToRun.mapNotNull { file ->
                    if (file.uri.authority == MediaStore.AUTHORITY) {
                        file.uri
                    } else {
                        val resolved = MetadataRestorer.resolveToMediaStoreUri(context, file.uri)
                        if (resolved != null && resolved.authority == MediaStore.AUTHORITY) resolved else null
                    }
                }

                if (!keepOriginal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mediaStoreUris.isNotEmpty()) {
                    try {
                        val pendingIntent = MediaStore.createWriteRequest(context.contentResolver, mediaStoreUris)
                        val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        writePermissionLauncher.launch(intentSenderRequest)
                    } catch (e: Exception) {
                        Log.e("ScannerScreen", "Failed to create write request: ${e.message}")
                        viewModel.addSelectedToQueue()
                        onNavigateToQueue()
                    }
                } else {
                    viewModel.addSelectedToQueue()
                    onNavigateToQueue()
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Active selection info row
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.surfaceTransparent),
                            border = BorderStroke(1.dp, PrimaryCyan.copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("ACTIVE SELECTION", color = PrimaryCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        folderName ?: "Gallery Selection",
                                        fontWeight = FontWeight.Black,
                                        color = TextWhite,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 16.sp
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconButton(
                                        onClick = { viewModel.refreshScan() },
                                        modifier = Modifier.background(AccentEmerald.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Scan", tint = AccentEmerald)
                                    }
                                    IconButton(
                                        onClick = { pickVideosLauncher.launch(Unit) },
                                        modifier = Modifier.background(PrimaryCyan.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Add from Gallery", tint = PrimaryCyan)
                                    }
                                    IconButton(
                                        onClick = {
                                            viewModel.loadFolderBuckets()
                                            showFolderPicker = true
                                        },
                                        modifier = Modifier.background(PrimaryCyan.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                    ) {
                                        Icon(Icons.Default.Folder, contentDescription = "Change Folder", tint = PrimaryCyan)
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(8.dp)) }

                    // COMPRESSION SETTINGS Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.15f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "COMPRESSION SETTINGS",
                                    color = PrimaryCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )

                                // 1. Codec choice
                                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Target Codec", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        InfoDot(
                                            if (targetCodec == "H.264")
                                                "H.264 (AVC): максимальная совместимость — воспроизводится на любых устройствах, включая старые ТВ и плееры. Файл крупнее при том же качестве."
                                            else
                                                "H.265 (HEVC): современный кодек — файл на 40–50% меньше при том же качестве. Поддерживается всеми новыми телефонами; очень старые ТВ/плееры могут не воспроизвести."
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf("HEVC", "H.264").forEach { codec ->
                                            val isSelected = targetCodec == codec
                                            SuggestionChip(
                                                onClick = { viewModel.setTargetCodec(codec) },
                                                label = { Text(codec, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                                                modifier = Modifier.weight(1f),
                                                colors = SuggestionChipDefaults.suggestionChipColors(
                                                    containerColor = if (isSelected) PrimaryCyan.copy(alpha = 0.2f) else Color.Transparent,
                                                    labelColor = if (isSelected) PrimaryCyan else TextGray
                                                )
                                            )
                                        }
                                    }
                                }

                                // 2. Resolution choice
                                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Resolution", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        InfoDot(
                                            "Original: размер кадра не меняется. " +
                                            "1080p / 720p: кадр уменьшается — деталей меньше, но файл заметно легче. " +
                                            "Вертикальные видео поворачиваются корректно."
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf("Original", "1080p", "720p").forEach { res ->
                                            val isSelected = targetResolution == res
                                            SuggestionChip(
                                                onClick = { viewModel.setTargetResolution(res) },
                                                label = { Text(res, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                                                modifier = Modifier.weight(1f),
                                                colors = SuggestionChipDefaults.suggestionChipColors(
                                                    containerColor = if (isSelected) PrimaryCyan.copy(alpha = 0.2f) else Color.Transparent,
                                                    labelColor = if (isSelected) PrimaryCyan else TextGray
                                                )
                                            )
                                        }
                                    }
                                }

                                // 3. Quality Preset choice
                                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Preset", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        InfoDot(
                                            "Quality: максимум деталей, экономия меньше (~25–45%). " +
                                            "Space: агрессивное сжатие, максимальная экономия (~55–75%), качество ниже. " +
                                            "Custom: задаёшь целевой битрейт слайдером сам. " +
                                            "Точная оценка показывается у каждого выбранного файла."
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        mapOf(
                                            "HIGH_QUALITY" to "Quality",
                                            "MAX_COMPRESSION" to "Space",
                                            "CUSTOM" to "Custom"
                                        ).forEach { (preset, labelText) ->
                                            val isSelected = qualityPreset == preset
                                            SuggestionChip(
                                                onClick = { viewModel.setQualityPreset(preset) },
                                                label = { Text(labelText, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                modifier = Modifier.weight(1f),
                                                colors = SuggestionChipDefaults.suggestionChipColors(
                                                    containerColor = if (isSelected) PrimaryCyan.copy(alpha = 0.2f) else Color.Transparent,
                                                    labelColor = if (isSelected) PrimaryCyan else TextGray
                                                )
                                            )
                                        }
                                    }
                                }

                                val presetDescription = when (qualityPreset) {
                                    "HIGH_QUALITY" -> "Quality: Keeps maximum detail, slightly larger size."
                                    "MAX_COMPRESSION" -> "Space: Saves maximum storage, lower bitrate."
                                    "CUSTOM" -> "Custom: Manually specify the target video encoding bitrate."
                                    else -> "Quality: Keeps maximum detail, slightly larger size."
                                }
                                Text(
                                    presetDescription,
                                    color = TextGray,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )

                                if (qualityPreset == "CUSTOM") {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Target Bitrate", color = TextWhite, fontSize = 14.sp)
                                            Text(
                                                text = String.format(Locale.US, "%.1f Mbps", customBitrateMbps),
                                                color = PrimaryCyan,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Slider(
                                            value = customBitrateMbps,
                                            onValueChange = { viewModel.setCustomBitrateMbps(it) },
                                            valueRange = 0.5f..30.0f,
                                            colors = SliderDefaults.colors(
                                                thumbColor = PrimaryCyan,
                                                activeTrackColor = PrimaryCyan,
                                                inactiveTrackColor = Color.Gray.copy(alpha = 0.24f)
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Output Mode card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.15f))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Output Mode", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        InfoDot(
                                            if (keepOriginal)
                                                "Save Copy: оригинал остаётся нетронутым, рядом сохраняется сжатая копия. Безопасно, но требует место в 2 раза больше на время операции."
                                            else
                                                "Replace Original: оригинал удаляется и заменяется сжатой версией с тем же именем и датой съёмки (файл остаётся на своём месте в галерее). Операция транзакционная: новая копия создаётся и проверяется ДО удаления оригинала."
                                        )
                                    }
                                    Text(
                                        if (keepOriginal) "Save Copy: Keeps original file" else "Replace Original: Replaces safely",
                                        color = TextGray,
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = keepOriginal,
                                    onCheckedChange = { viewModel.setKeepOriginal(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.Black,
                                        checkedTrackColor = PrimaryCyan,
                                        uncheckedThumbColor = TextGray,
                                        uncheckedTrackColor = LocalAppColors.current.unchecked
                                    )
                                )
                            }
                        }
                    }

                    // Header and sorting
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (onlyLargeFiles) "${scannedFiles.size} large videos (> 100 MB)"
                                else "${scannedFiles.size} videos found",
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Box {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DarkSurface)
                                        .clickable { sortMenuExpanded = true }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = when (sortOrder) {
                                            MainViewModel.SortOrder.NAME_ASC -> "Name (A-Z)"
                                            MainViewModel.SortOrder.NAME_DESC -> "Name (Z-A)"
                                            MainViewModel.SortOrder.SIZE_ASC -> "Size (Asc)"
                                            MainViewModel.SortOrder.SIZE_DESC -> "Size (Desc)"
                                            MainViewModel.SortOrder.DATE_ASC -> "Date (Oldest)"
                                            MainViewModel.SortOrder.DATE_DESC -> "Date (Newest)"
                                        },
                                        color = PrimaryCyan,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                DropdownMenu(
                                    expanded = sortMenuExpanded,
                                    onDismissRequest = { sortMenuExpanded = false },
                                    modifier = Modifier.background(DarkSurface)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Name (A-Z)", color = TextWhite) },
                                        onClick = {
                                            viewModel.setSortOrder(MainViewModel.SortOrder.NAME_ASC)
                                            sortMenuExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Name (Z-A)", color = TextWhite) },
                                        onClick = {
                                            viewModel.setSortOrder(MainViewModel.SortOrder.NAME_DESC)
                                            sortMenuExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Size (Smallest first)", color = TextWhite) },
                                        onClick = {
                                            viewModel.setSortOrder(MainViewModel.SortOrder.SIZE_ASC)
                                            sortMenuExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Size (Largest first)", color = TextWhite) },
                                        onClick = {
                                            viewModel.setSortOrder(MainViewModel.SortOrder.SIZE_DESC)
                                            sortMenuExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Date (Oldest first)", color = TextWhite) },
                                        onClick = {
                                            viewModel.setSortOrder(MainViewModel.SortOrder.DATE_ASC)
                                            sortMenuExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Date (Newest first)", color = TextWhite) },
                                        onClick = {
                                            viewModel.setSortOrder(MainViewModel.SortOrder.DATE_DESC)
                                            sortMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Filters (ignore compressed / large files) + Select all / Clear all
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val ignoreCompressed by viewModel.ignoreCompressed.collectAsState()
                                FilterChip(
                                    selected = ignoreCompressed,
                                    onClick = { viewModel.setIgnoreCompressed(!ignoreCompressed) },
                                    label = { Text("Hide compressed", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = DarkSurface,
                                        selectedContainerColor = AccentEmerald.copy(alpha = 0.2f),
                                        selectedLabelColor = AccentEmerald,
                                        labelColor = TextGray
                                    )
                                )
                                FilterChip(
                                    selected = onlyLargeFiles,
                                    onClick = { viewModel.setOnlyLargeFiles(!onlyLargeFiles) },
                                    label = { Text("> 100 MB", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = DarkSurface,
                                        selectedContainerColor = PrimaryCyan.copy(alpha = 0.2f),
                                        selectedLabelColor = PrimaryCyan,
                                        labelColor = TextGray
                                    )
                                )
                            }
                            Row {
                                TextButton(onClick = { viewModel.toggleAllFilesSelection(true) }) {
                                    Text("Select All", color = PrimaryCyan)
                                }
                                TextButton(onClick = { viewModel.toggleAllFilesSelection(false) }) {
                                    Text("Clear All", color = TextGray)
                                }
                            }
                        }
                    }

                    // Checklist of files
                    items(
                        items = scannedFiles,
                        key = { it.uri.toString() }
                    ) { file ->
                        val isSelected = file.isSelected
                        val borderAlpha by animateFloatAsState(targetValue = if (isSelected) 0.6f else 0.1f)
                        val bgAlpha by animateFloatAsState(targetValue = if (isSelected) 0.4f else 0.2f)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(LocalAppColors.current.rowBackground.copy(alpha = bgAlpha))
                                .clickable { viewModel.toggleFileSelection(file.uri) }
                                .padding(12.dp)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            1.dp,
                                            PrimaryCyan.copy(alpha = borderAlpha),
                                            RoundedCornerShape(12.dp)
                                        )
                                    } else {
                                        Modifier.border(
                                            1.dp,
                                            Color.Gray.copy(alpha = borderAlpha),
                                            RoundedCornerShape(12.dp)
                                        )
                                    }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = file.isSelected,
                                onCheckedChange = { viewModel.toggleFileSelection(file.uri) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = PrimaryCyan,
                                    uncheckedColor = TextGray.copy(alpha = 0.5f)
                                )
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    file.name,
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        Formatter.formatShortFileSize(context, file.size),
                                        color = TextGray,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    val estimate = if (file.isSelected) sizeEstimates[file.uri.toString()] else null
                                    if (estimate != null && estimate in 1 until file.size) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "→ ~${Formatter.formatShortFileSize(context, estimate)} (−${(100L - estimate * 100 / file.size)}%)",
                                            color = SuccessColor,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                            // Preview button
                            IconButton(
                                onClick = { previewFile = file },
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(PrimaryCyan.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Preview video",
                                    tint = PrimaryCyan,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    if (scannedFiles.isEmpty() && rawScannedCount > 0) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = AccentEmerald.copy(alpha = 0.08f)),
                                border = BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.3f))
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = AccentEmerald,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "All $rawScannedCount files are already compressed",
                                        color = TextWhite,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    TextButton(onClick = { viewModel.setIgnoreCompressed(false) }) {
                                        Text("Show full list", color = PrimaryCyan, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }

                // Pinned bottom Run bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    LocalAppColors.current.scrim.copy(alpha = 0f),
                                    LocalAppColors.current.scrim.copy(alpha = 0.95f)
                                )
                            )
                        )
                        .padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 12.dp)
                ) {
                    Button(
                        onClick = onRunClick,
                        enabled = selectedCount > 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryCyan,
                            disabledContainerColor = DarkSurface
                        )
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = if (selectedCount > 0) Color.Black else TextGray
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (selectedSizeBytes > 0) "Run ($selectedCount) · ${selectedSizeBytes / (1024 * 1024)} MB"
                                else "Run ($selectedCount)",
                                color = if (selectedCount > 0) Color.Black else TextGray,
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp
                            )
                            if (hasFullEstimate && estimatedTotalBytes in 1 until selectedSizeBytes) {
                                Text(
                                    "≈ ${estimatedTotalBytes / (1024 * 1024)} MB (−${100L - estimatedTotalBytes * 100 / selectedSizeBytes}%)",
                                    color = Color.Black.copy(alpha = 0.65f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
