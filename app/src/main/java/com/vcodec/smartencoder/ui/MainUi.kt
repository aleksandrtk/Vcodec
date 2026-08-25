package com.vcodec.smartencoder.ui

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult

import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vcodec.smartencoder.BuildConfig
import com.vcodec.smartencoder.data.TaskStatus
import com.vcodec.smartencoder.data.TranscodeTask
import com.vcodec.smartencoder.ui.theme.AccentEmerald
import com.vcodec.smartencoder.ui.theme.AlertAmber
import com.vcodec.smartencoder.ui.theme.AlertRed
import com.vcodec.smartencoder.ui.theme.DarkSurface
import com.vcodec.smartencoder.ui.theme.LocalAppColors
import com.vcodec.smartencoder.ui.theme.PrimaryCyan
import com.vcodec.smartencoder.ui.theme.SuccessColor
import com.vcodec.smartencoder.ui.theme.TextGray
import com.vcodec.smartencoder.ui.theme.TextWhite
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartEncoderAppContent(viewModel: MainViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Scanner", "Queue", "Savings & History")
    val showUpdateDialog by viewModel.showUpdateDialog.collectAsState()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Smart Encoder",
                        fontWeight = FontWeight.Black,
                        color = TextWhite,
                        letterSpacing = 0.5.sp
                    )
                },
                actions = {
                    val isCheckingUpdate by viewModel.isCheckingUpdate.collectAsState()
                    IconButton(
                        onClick = { viewModel.toggleTheme() }
                    ) {
                        Icon(
                            imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle theme",
                            tint = PrimaryCyan
                        )
                    }
                    IconButton(
                        onClick = { viewModel.checkForUpdates(BuildConfig.VERSION_NAME, manual = true) }
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                color = PrimaryCyan,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = "Check for Updates",
                                tint = PrimaryCyan
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LocalAppColors.current.appBarGlass
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = LocalAppColors.current.navGlass,
                tonalElevation = 8.dp
            ) {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            Icon(
                                imageVector = when (index) {
                                    0 -> Icons.Default.Folder
                                    1 -> Icons.Default.Refresh
                                    else -> Icons.Default.CheckCircle
                                },
                                contentDescription = title
                            )
                        },
                        label = { Text(title, fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = LocalAppColors.current.onAccent,
                            selectedTextColor = PrimaryCyan,
                            indicatorColor = PrimaryCyan,
                            unselectedIconColor = TextGray,
                            unselectedTextColor = TextGray
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            LocalAppColors.current.backgroundTop,
                            LocalAppColors.current.backgroundMid,
                            LocalAppColors.current.backgroundBottom
                        )
                    )
                )
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> ScannerScreen(viewModel, onNavigateToQueue = { selectedTab = 1 })
                1 -> QueueScreen(viewModel)
                2 -> HistoryScreen(viewModel)
            }

            if (showUpdateDialog) {
                UpdateDialog(viewModel = viewModel)
            }
        }
    }
}

/**
 * Fullscreen video preview dialog backed by ExoPlayer.
 * Used to identify a video before adding it to the compression queue.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPreviewDialog(uri: android.net.Uri, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val player = remember(uri) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(uri) {
        onDispose { player.release() }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xEE070A13))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close preview", tint = Color.White)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Preview — the file is not modified",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )
        }
    }
}

/**
 * Small "?" badge that shows an explanation popup when tapped.
 */
@Composable
fun InfoDot(infoText: String) {
    var showInfo by remember { mutableStateOf(false) }
    Box {
        Text(
            "?",
            color = PrimaryCyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(PrimaryCyan.copy(alpha = 0.15f))
                .clickable { showInfo = !showInfo }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        if (showInfo) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.TopStart,
                onDismissRequest = { showInfo = false }
            ) {
                Card(
                    modifier = Modifier.padding(8.dp).widthIn(max = 280.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryCyan.copy(alpha = 0.35f))
                ) {
                    Text(
                        infoText,
                        color = TextGray,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

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
        contract = object : androidx.activity.result.contract.ActivityResultContract<Unit, List<Uri>>() {
            override fun createIntent(context: android.content.Context, input: Unit): android.content.Intent {
                // ACTION_PICK directly opens the default system gallery app (Samsung Gallery on Samsung, Google Photos on Pixel)
                return android.content.Intent(android.content.Intent.ACTION_PICK, android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI).apply {
                    putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }

            override fun parseResult(resultCode: Int, intent: android.content.Intent?): List<Uri> {
                if (resultCode != android.app.Activity.RESULT_OK || intent == null) return emptyList()
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
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.addSelectedToQueue()
            onNavigateToQueue()
        } else {
            // User denied the write request: Replace mode cannot modify originals.
            android.widget.Toast.makeText(
                context,
                "Write permission denied. Files will be saved as copies instead.",
                android.widget.Toast.LENGTH_LONG
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
            // Redesigned Start screen: First select file or folder
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
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
                    border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryCyan.copy(alpha = 0.4f)),
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
            // File/folder selected! Show files checklist, then settings, pinned Run button at the bottom.
            val selectedCount = scannedFiles.count { it.isSelected }
            val selectedFiles = scannedFiles.filter { it.isSelected }
            val selectedSizeBytes = selectedFiles.sumOf { it.size }
            val estimatedTotalBytes = selectedFiles.sumOf { file -> sizeEstimates[file.uri.toString()] ?: 0L }
            val hasFullEstimate = selectedFiles.isNotEmpty() &&
                selectedFiles.all { sizeEstimates.containsKey(it.uri.toString()) }

            // Recompute estimates whenever the selection or compression settings change
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

            val onRunClick: () -> Unit = {
                val selectedFiles = scannedFiles.filter { it.isSelected }
                val mediaStoreUris = selectedFiles.mapNotNull { file ->
                    if (file.uri.authority == android.provider.MediaStore.AUTHORITY) {
                        file.uri
                    } else {
                        val resolved = com.vcodec.smartencoder.metadata.MetadataRestorer.resolveToMediaStoreUri(context, file.uri)
                        if (resolved != null && resolved.authority == android.provider.MediaStore.AUTHORITY) resolved else null
                    }
                }

                if (!keepOriginal && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && mediaStoreUris.isNotEmpty()) {
                    try {
                        val pendingIntent = android.provider.MediaStore.createWriteRequest(context.contentResolver, mediaStoreUris)
                        val intentSenderRequest = androidx.activity.result.IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        writePermissionLauncher.launch(intentSenderRequest)
                    } catch (e: Exception) {
                        android.util.Log.e("ScannerScreen", "Failed to create write request: ${e.message}")
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
                // Item 1: Active selection info row
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.surfaceTransparent),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryCyan.copy(alpha = 0.2f))
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
                                    Icon(Icons.Default.Refresh, contentDescription = "Change Folder", tint = PrimaryCyan)
                                }
                            }
                        }
                    }
                }

                // Item 4: Settings separator
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Item 5: COMPRESSION SETTINGS Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.15f))
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
                                            label = { Text(codec, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
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
                                            label = { Text(res, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
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
                                            label = { Text(labelText, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                                            text = String.format(java.util.Locale.US, "%.1f Mbps", customBitrateMbps),
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

                // Item 6: Output Mode card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.15f))
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


                // Item 2: Header and sorting
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

                // Item 3: Large-file filter + Select all / Clear all
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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

                // Items list: checklist of files
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
                        // Preview button: play the video before deciding
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

                // Item 7 (moved): pinned Run button now lives below the list
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Pinned bottom Run bar: always visible, no scrolling required
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

@Composable
fun QueueScreen(viewModel: MainViewModel) {
    val tasks by viewModel.allTasks.collectAsState()
    val context = LocalContext.current

    val activeTask = tasks.find { 
        it.status == TaskStatus.PROCESSING || it.status == TaskStatus.ANALYZING 
    }
    val pendingTasks = tasks.filter { 
        it.status == TaskStatus.PENDING || it.status == TaskStatus.PAUSED || it.status == TaskStatus.FAILED 
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Start Queue button (if queue has pending files and is idle)
        val hasPending = pendingTasks.any { it.status == TaskStatus.PENDING }
        if (activeTask == null && hasPending) {
            Button(
                onClick = { viewModel.startQueue() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Start Queue",
                    tint = Color.Black
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Start Compression",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }

        // Active transcode section
        if (activeTask != null) {
            ActiveTaskCard(activeTask, context, viewModel)
            Spacer(modifier = Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Queue (${pendingTasks.size} files)",
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            // NOTE: no "Clear Completed" here on purpose — completed tasks are the
            // permanent Savings & History record (total space saved). Deleting them
            // would wipe the user's compression stats.
        }

        if (pendingTasks.isEmpty() && activeTask == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Empty Queue",
                        tint = TextGray.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Your queue is empty",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Select files in the Scanner tab to begin compression.",
                        color = TextGray,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pendingTasks) { task ->
                    QueueTaskItem(task, context, viewModel)
                }
            }
        }
    }
}

@Composable
fun ActiveTaskCard(task: TranscodeTask, context: android.content.Context, viewModel: MainViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.surfaceTransparent),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, PrimaryCyan.copy(alpha = 0.35f)), // Bright cyan-neon border
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (task.status == TaskStatus.ANALYZING) "Analyzing video details..." else "Optimizing video size...",
                        color = PrimaryCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        task.fileName,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Dynamic Temperature Display
                if (task.cpuTemp > 0) {
                    val tempColor by animateColorAsState(
                        targetValue = when {
                            task.cpuTemp > 45.0f -> AlertRed
                            task.cpuTemp > 40.0f -> AlertAmber
                            else -> PrimaryCyan
                        }
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(tempColor.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            String.format(Locale.getDefault(), "%.0f°C", task.cpuTemp),
                            color = tempColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = PrimaryCyan,
                trackColor = LocalAppColors.current.unchecked
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    String.format(Locale.getDefault(), "%.1f%% Completed", task.progress * 100),
                    color = TextWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (task.status == TaskStatus.PROCESSING && task.targetBitrate > 0) {
                    Text(
                        "Target: ${task.targetCodec} @ ${(task.targetBitrate / 1_000_000.0).format(1)} Mbps",
                        color = TextGray,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Hardware specs & metadata badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "HW Info",
                        tint = AccentEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (task.isHdr) "Snapdragon HW HEVC 10-bit (HDR)" else "Snapdragon HW HEVC 8-bit",
                        color = AccentEmerald,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    "Original: ${Formatter.formatShortFileSize(context, task.originalSize)}",
                    color = TextGray,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Color.Gray.copy(alpha = 0.15f))
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons (Pause & Cancel)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { viewModel.pauseTask(task.id) },
                    colors = ButtonDefaults.textButtonColors(contentColor = AlertAmber)
                ) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = "Pause Task",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pause", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(16.dp))
                TextButton(
                    onClick = { viewModel.deleteTask(task.id) },
                    colors = ButtonDefaults.textButtonColors(contentColor = AlertRed)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Cancel Task",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cancel", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun QueueTaskItem(task: TranscodeTask, context: android.content.Context, viewModel: MainViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surfaceTransparent) // Transparent surface
            .border(0.5.dp, Color.Gray.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                task.fileName,
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    Formatter.formatShortFileSize(context, task.originalSize),
                    color = TextGray,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                val statusText = when (task.status) {
                    TaskStatus.PENDING -> "In Queue"
                    TaskStatus.PAUSED -> "Paused"
                    TaskStatus.FAILED -> "Failed: ${task.errorMessage ?: ""}"
                    else -> task.status.name
                }
                val statusColor = when (task.status) {
                    TaskStatus.PENDING -> PrimaryCyan
                    TaskStatus.PAUSED -> AlertAmber
                    TaskStatus.FAILED -> AlertRed
                    else -> TextGray
                }
                Text(
                    statusText,
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Action buttons
        Row {
            if (task.status == TaskStatus.PENDING) {
                IconButton(onClick = { viewModel.pauseTask(task.id) }) {
                    Icon(imageVector = Icons.Default.Pause, contentDescription = "Pause", tint = AlertAmber)
                }
            } else if (task.status == TaskStatus.PAUSED) {
                IconButton(onClick = { viewModel.resumeTask(task.id) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = PrimaryCyan)
                }
            }
            IconButton(onClick = { viewModel.deleteTask(task.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = AlertRed)
            }
        }
    }
}

@Composable
fun HistoryScreen(viewModel: MainViewModel) {
    val tasks by viewModel.allTasks.collectAsState()
    val totalSaved by viewModel.totalSpaceSaved.collectAsState()
    val context = LocalContext.current

    val completedTasks = tasks.filter { it.status == TaskStatus.COMPLETED }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Space saved summary panel with glassmorphism card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.surfaceTransparent), // Transparent surface
            border = androidx.compose.foundation.BorderStroke(1.5.dp, PrimaryCyan.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "TOTAL STORAGE RECLAIMED",
                    color = PrimaryCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                val formattedSaved = if (totalSaved != null && totalSaved!! > 0L) {
                    Formatter.formatShortFileSize(context, totalSaved!!)
                } else {
                    "0 GB"
                }
                Text(
                    formattedSaved,
                    color = TextWhite,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Through ${completedTasks.size} successfully compressed files",
                    color = TextGray,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // App Version & In-App OTA Update card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.surfaceTransparent),
            border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryCyan.copy(alpha = 0.25f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "VCodec Smart Encoder",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "Installed: v${BuildConfig.VERSION_NAME}",
                        color = TextGray,
                        fontSize = 12.sp
                    )
                }

                val isCheckingUpdate by viewModel.isCheckingUpdate.collectAsState()
                OutlinedButton(
                    onClick = { viewModel.checkForUpdates(BuildConfig.VERSION_NAME, manual = true) },
                    enabled = !isCheckingUpdate,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryCyan),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryCyan.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(
                            color = PrimaryCyan,
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Checking...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Check Updates", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Compression History",
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }

        if (completedTasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("No operations completed yet.", color = TextGray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(completedTasks) { task ->
                    HistoryItem(task, context, viewModel)
                }
            }
        }
    }
}

@Composable
fun FolderPickerDialog(
    buckets: List<MainViewModel.FolderBucket>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSelect: (MainViewModel.FolderBucket) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextGray) }
        },
        containerColor = DarkSurface,
        titleContentColor = TextWhite,
        textContentColor = TextGray,
        title = { Text("Select Folder to Scan", fontWeight = FontWeight.Bold) },
        text = {
            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = PrimaryCyan)
                }
            } else if (buckets.isEmpty()) {
                Text("No folders with videos found.", modifier = Modifier.padding(vertical = 16.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(buckets) { bucket ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(LocalAppColors.current.surfaceTransparent)
                                .clickable { onSelect(bucket) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = PrimaryCyan,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    bucket.name,
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (bucket.relativePath.isNotBlank()) {
                                    Text(
                                        bucket.relativePath.trim('/'),
                                        color = TextGray,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "${bucket.videoCount} • ${Formatter.formatShortFileSize(LocalContext.current, bucket.totalSizeBytes)}",
                                color = PrimaryCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun HistoryItem(task: TranscodeTask, context: android.content.Context, viewModel: MainViewModel) {
    var showLocate by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surfaceTransparent) // Transparent surface
            .border(0.5.dp, Color.Gray.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = "Success",
            tint = AccentEmerald,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                task.fileName,
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(modifier = Modifier.padding(top = 4.dp)) {
                val origStr = Formatter.formatShortFileSize(context, task.originalSize)
                val compStr = Formatter.formatShortFileSize(context, task.compressedSize)
                val savedPercent = ((task.originalSize - task.compressedSize).toFloat() / task.originalSize * 100).toInt()
                Text(
                    "$origStr → $compStr ($savedPercent% saved)",
                    color = TextGray,
                    fontSize = 12.sp
                )
            }
        }

        // Open in Gallery Button
        IconButton(
            onClick = {
                val targetUri = task.destUri ?: task.sourceUri
                openVideoInGallery(context, targetUri)
            }
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Open in Gallery",
                tint = PrimaryCyan
            )
        }

        // Locate the file's position in the gallery timeline (in-app, auto-scrolled & highlighted)
        IconButton(
            onClick = { showLocate = true }
        ) {
            Icon(
                imageVector = Icons.Default.GridView,
                contentDescription = "Show in Gallery Grid",
                tint = AccentEmerald
            )
        }
    }

    if (showLocate) {
        LocateInTimelineDialog(
            task = task,
            onDismiss = { showLocate = false }
        )
    }
}

/** A video entry in the gallery timeline, newest first. */
private data class TimelineVideo(val id: Long, val name: String, val dateMs: Long)

/**
 * In-app "Where is this file?" view: shows the device's video timeline (MediaStore,
 * newest first) as a thumbnail grid, auto-scrolled to the given task's file with a
 * highlighted frame — so the user can instantly see its chronological position
 * (e.g. confirm it did NOT jump to the top after compression).
 *
 * Third-party gallery apps expose no API for scrolling to an item without playing it,
 * so this is implemented natively.
 */
@Composable
fun LocateInTimelineDialog(task: TranscodeTask, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var videos by remember { mutableStateOf<List<TimelineVideo>?>(null) }
    var targetPositionLabel by remember { mutableStateOf<String?>(null) }

    // Resolve the real MediaStore id of the compressed file (handles MediaStore,
    // SAF document and file-path URIs)
    val targetId = remember(task) {
        sequenceOf(task.destUri, task.sourceUri).filterNotNull()
            .mapNotNull { uriStr ->
                try {
                    val uri = Uri.parse(uriStr)
                    com.vcodec.smartencoder.metadata.MetadataRestorer
                        .resolveToMediaStoreUri(context, uri)
                        ?.takeIf { it.authority == android.provider.MediaStore.AUTHORITY }
                        ?.lastPathSegment?.toLongOrNull()
                        ?: uri.lastPathSegment?.toLongOrNull()
                } catch (_: Exception) {
                    null
                }
            }
            .firstOrNull()
    }
    val targetName = task.fileName.lowercase()

    LaunchedEffect(task) {
        val list = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.query(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        android.provider.MediaStore.Video.VideoColumns._ID,
                        android.provider.MediaStore.Video.VideoColumns.DISPLAY_NAME,
                        android.provider.MediaStore.Video.VideoColumns.DATE_MODIFIED
                    ),
                    null, null,
                    "${android.provider.MediaStore.Video.VideoColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.VideoColumns._ID)
                    val nameIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.DISPLAY_NAME)
                    val dateIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.VideoColumns.DATE_MODIFIED)
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                TimelineVideo(
                                    id = cursor.getLong(idIdx),
                                    name = cursor.getString(nameIdx).orEmpty(),
                                    dateMs = if (dateIdx != -1 && !cursor.isNull(dateIdx)) cursor.getLong(dateIdx) * 1000L else 0L
                                )
                            )
                        }
                    }
                } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        videos = list
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = PrimaryCyan) }
        },
        containerColor = DarkSurface,
        titleContentColor = TextWhite,
        textContentColor = TextGray,
        title = {
            Column {
                Text("Position in Gallery", fontWeight = FontWeight.Bold)
                Text(
                    targetPositionLabel ?: "Newest first \u2022 looking for ${task.fileName}",
                    fontSize = 11.sp,
                    color = AccentEmerald.takeIf { targetPositionLabel != null } ?: TextGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            when {
                videos == null -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator(color = PrimaryCyan) }
                videos!!.isEmpty() -> Text("No videos found on this device.")
                else -> {
                    val list = videos!!
                    val targetIndex = list.indexOfFirst { v ->
                        v.id == targetId ||
                            v.name.lowercase() == targetName ||
                            v.name.lowercase().startsWith(targetName.removeSuffix(".mp4"))
                    }
                    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

                    // Auto-scroll so the highlighted target tile is at the top of the viewport
                    LaunchedEffect(list) {
                        if (targetIndex >= 0) {
                            targetPositionLabel =
                                "Position ${targetIndex + 1} of ${list.size} newest"
                            gridState.scrollToItem(targetIndex)
                        }
                    }

                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                        state = gridState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(list.size) { index ->
                            val video = list[index]
                            val isTarget = index == targetIndex
                            val contentUri = android.content.ContentUris.withAppendedId(
                                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video.id
                            )
                            Column {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.3f))
                                        .border(
                                            if (isTarget) 2.dp else 0.5.dp,
                                            if (isTarget) AccentEmerald else Color.Gray.copy(alpha = 0.25f),
                                            RoundedCornerShape(8.dp)
                                        )
                                ) {
                                    VideoThumbnail(uri = contentUri, modifier = Modifier.fillMaxSize())
                                    if (isTarget) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "This file",
                                            tint = AccentEmerald,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(4.dp)
                                                .size(18.dp)
                                        )
                                        Text(
                                            "THIS FILE",
                                            color = Color.Black,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(3.dp)
                                                .background(AccentEmerald, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                if (isTarget) {
                                    Text(
                                        "\u2191 ${video.dateMs.takeIf { it > 0 }?.let { java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) } ?: "?"}",
                                        color = AccentEmerald,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun VideoThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(uri) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            withContext(Dispatchers.IO) {
                bitmap = try {
                    context.contentResolver.loadThumbnail(uri, android.util.Size(200, 200), null)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        } else {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

fun openVideoInGallery(context: android.content.Context, uriString: String?) {
    if (uriString == null) return
    try {
        val uri = Uri.parse(uriString)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Cannot open video: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

// Utility extension function to format doubles to strings with specific decimal length
fun Double.format(digits: Int) = String.format(Locale.getDefault(), "%.${digits}f", this)

@Composable
fun UpdateDialog(viewModel: MainViewModel) {
    val context = LocalContext.current
    val isChecking by viewModel.isCheckingUpdate.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val isDownloading by viewModel.isDownloadingUpdate.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val updateError by viewModel.updateError.collectAsState()

    AlertDialog(
        onDismissRequest = {
            if (!isDownloading) viewModel.dismissUpdateDialog()
        },
        containerColor = LocalAppColors.current.surface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = "Update",
                        tint = PrimaryCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        if (updateInfo?.hasUpdate == true) "Update Available" else if (isChecking) "Checking Updates..." else "App Up to Date",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                if (!isDownloading) {
                    IconButton(onClick = { viewModel.dismissUpdateDialog() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextGray)
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isChecking) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = PrimaryCyan)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Checking GitHub for releases...", color = TextGray, fontSize = 14.sp)
                    }
                } else if (updateError != null) {
                    Text(
                        "Could not check for updates:\n$updateError",
                        color = AlertRed,
                        fontSize = 14.sp
                    )
                } else if (updateInfo?.hasUpdate == true) {
                    val info = updateInfo!!
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Text("Installed: v${BuildConfig.VERSION_NAME}", color = TextGray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(PrimaryCyan.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "Latest: ${info.rawTagName}",
                                color = PrimaryCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (info.releaseName.isNotEmpty() && info.releaseName != info.rawTagName) {
                        Text(
                            info.releaseName,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    Text("What's New:", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(LocalAppColors.current.surfaceTransparent)
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            info.changelog.ifEmpty { "Performance improvements and bug fixes." },
                            color = TextGray,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }

                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = PrimaryCyan,
                            trackColor = LocalAppColors.current.unchecked
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            String.format(Locale.getDefault(), "Downloading update: %.0f%%", downloadProgress * 100),
                            color = PrimaryCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text(
                        "You are running the latest version of VCodec (v${BuildConfig.VERSION_NAME}). No new updates found on GitHub.",
                        color = TextGray,
                        fontSize = 14.sp
                    )
                }
            }
        },
        confirmButton = {
            if (updateInfo?.hasUpdate == true && !isChecking) {
                Button(
                    onClick = { viewModel.startDownloadAndInstall(context) },
                    enabled = !isDownloading,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Downloading...", color = Color.Black, fontWeight = FontWeight.Bold)
                    } else if (updateInfo?.downloadUrl != null) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download & Install", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Release Page", fontWeight = FontWeight.Bold)
                    }
                }
            } else if (!isChecking) {
                TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
                    Text("OK", color = PrimaryCyan, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (updateInfo?.hasUpdate == true && !isDownloading) {
                TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
                    Text("Later", color = TextGray)
                }
            }
        }
    )
}
