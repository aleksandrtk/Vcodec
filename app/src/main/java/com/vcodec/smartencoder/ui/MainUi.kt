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
import com.vcodec.smartencoder.data.TaskStatus
import com.vcodec.smartencoder.data.TranscodeTask
import com.vcodec.smartencoder.ui.theme.AlertAmber
import com.vcodec.smartencoder.ui.theme.AlertRed
import com.vcodec.smartencoder.ui.theme.DarkSurface
import com.vcodec.smartencoder.ui.theme.PrimaryCyan
import com.vcodec.smartencoder.ui.theme.TextGray
import com.vcodec.smartencoder.ui.theme.TextWhite
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartEncoderAppContent(viewModel: MainViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Scanner", "Queue", "Savings & History")

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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0x7F0F172A) // Glassy dark
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xCC0F172A), // Glassy dark
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
                            selectedIconColor = Color.Black,
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
                            Color(0xFF070A13), // Deep cosmic black-blue
                            Color(0xFF0F172A), // Deep slate navy
                            Color(0xFF1E1E38)  // Subtle indigo bottom glow
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

    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.selectFolder(uri)
        }
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
    ) { _ ->
        viewModel.addSelectedToQueue()
        onNavigateToQueue()
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
                    onClick = { openFolderLauncher.launch(null) },
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
            // File/folder selected! Show files checklist, then settings, then start button.
            val selectedCount = scannedFiles.count { it.isSelected }
            var sortMenuExpanded by remember { mutableStateOf(false) }
            val sortOrder by viewModel.sortOrder.collectAsState()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Item 1: Active selection info row
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x3D1E293B)),
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
                                    onClick = { pickVideosLauncher.launch(Unit) },
                                    modifier = Modifier.background(PrimaryCyan.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Add from Gallery", tint = PrimaryCyan)
                                }
                                IconButton(
                                    onClick = { openFolderLauncher.launch(null) },
                                    modifier = Modifier.background(PrimaryCyan.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Change Folder", tint = PrimaryCyan)
                                }
                            }
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
                            "${scannedFiles.size} videos found",
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

                // Item 3: Select all / Clear all
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { viewModel.toggleAllFilesSelection(true) }) {
                            Text("Select All", color = PrimaryCyan)
                        }
                        TextButton(onClick = { viewModel.toggleAllFilesSelection(false) }) {
                            Text("Clear All", color = TextGray)
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
                            .background(Color(0xFF1E293B).copy(alpha = bgAlpha))
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
                            Text(
                                Formatter.formatShortFileSize(context, file.size),
                                color = TextGray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
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
                                Text("Target Codec", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
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
                                Text("Resolution", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
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
                                Text("Preset", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    mapOf(
                                        "SMART" to "Smart",
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
                                else -> "Smart: Recommended balance of size & visual quality."
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
                                Text("Output Mode", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
                                    uncheckedTrackColor = Color(0xFF334155)
                                )
                            )
                        }
                    }
                }

                // Item 7: Start Button
                item {
                    Button(
                        onClick = {
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
                        },
                        enabled = selectedCount > 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryCyan,
                            disabledContainerColor = DarkSurface
                        )
                    ) {
                        Text(
                            "Start Compress ($selectedCount)",
                            color = if (selectedCount > 0) Color.Black else TextGray,
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
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
            if (tasks.any { it.status == TaskStatus.COMPLETED }) {
                TextButton(onClick = { viewModel.clearCompleted() }) {
                    Text("Clear Completed", color = TextGray)
                }
            }
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
        colors = CardDefaults.cardColors(containerColor = Color(0x591E293B)), // Transparent surface
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
                    val label = when {
                        task.cpuTemp > 45.0f -> "Thermal Safety Active"
                        task.cpuTemp > 40.0f -> "Cooling Device"
                        else -> "Optimal Temp"
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(tempColor.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "$label (${String.format(Locale.getDefault(), "%.0f°C", task.cpuTemp)})",
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
                trackColor = Color(0xFF334155)
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
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (task.isHdr) "Snapdragon HW HEVC 10-bit (HDR)" else "Snapdragon HW HEVC 8-bit",
                        color = Color(0xFF10B981),
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
            .background(Color(0x331E293B)) // Transparent surface
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
            colors = CardDefaults.cardColors(containerColor = Color(0x3D1E293B)), // Transparent surface
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

        Spacer(modifier = Modifier.height(20.dp))

        var isFixingDates by remember { mutableStateOf(false) }

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
            if (completedTasks.isNotEmpty()) {
                Button(
                    onClick = {
                        isFixingDates = true
                        viewModel.fixAllCompletedTasksDates { success, failed ->
                            isFixingDates = false
                            android.widget.Toast.makeText(
                                context,
                                "Dates restored for $success files (Failed/skipped: $failed)",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    enabled = !isFixingDates,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryCyan.copy(alpha = 0.2f),
                        contentColor = PrimaryCyan
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    if (isFixingDates) {
                        CircularProgressIndicator(
                            color = PrimaryCyan,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Fix Dates",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Fix Gallery Dates", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
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
fun HistoryItem(task: TranscodeTask, context: android.content.Context, viewModel: MainViewModel) {
    var isFixing by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x331E293B)) // Transparent surface
            .border(0.5.dp, Color.Gray.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = "Success",
            tint = Color(0xFF10B981),
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

        // Fix Date Button for this specific item
        IconButton(
            onClick = {
                isFixing = true
                viewModel.fixSingleTaskDate(task.id) { success ->
                    isFixing = false
                    android.widget.Toast.makeText(
                        context,
                        if (success) "Date restored for ${task.fileName}" else "Failed to restore date",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            },
            enabled = !isFixing
        ) {
            if (isFixing) {
                CircularProgressIndicator(
                    color = PrimaryCyan,
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Restore Original Date",
                    tint = PrimaryCyan.copy(alpha = 0.7f)
                )
            }
        }

        // Open in Gallery Button
        IconButton(
            onClick = {
                val targetUri = task.destUri ?: task.sourceUri
                openVideoInGallery(context, targetUri)
            },
            enabled = !isFixing
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Open in Gallery",
                tint = PrimaryCyan
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
