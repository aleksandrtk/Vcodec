package com.vcodec.smartencoder.ui.screens.history

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vcodec.smartencoder.BuildConfig
import com.vcodec.smartencoder.data.TaskStatus
import com.vcodec.smartencoder.data.TranscodeTask
import com.vcodec.smartencoder.ui.MainViewModel
import com.vcodec.smartencoder.ui.components.LocateInTimelineDialog
import com.vcodec.smartencoder.ui.components.openVideoInGallery
import com.vcodec.smartencoder.ui.theme.AccentEmerald
import com.vcodec.smartencoder.ui.theme.LocalAppColors
import com.vcodec.smartencoder.ui.theme.PrimaryCyan
import com.vcodec.smartencoder.ui.theme.TextGray
import com.vcodec.smartencoder.ui.theme.TextWhite

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
        // Space saved summary panel
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.surfaceTransparent),
            border = BorderStroke(1.5.dp, PrimaryCyan.copy(alpha = 0.3f))
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
            border = BorderStroke(1.dp, PrimaryCyan.copy(alpha = 0.25f))
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
                    border = BorderStroke(1.dp, PrimaryCyan.copy(alpha = 0.5f)),
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
fun HistoryItem(task: TranscodeTask, context: Context, viewModel: MainViewModel) {
    var showLocate by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surfaceTransparent)
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
                val savedPercent = if (task.originalSize > 0L) {
                    ((task.originalSize - task.compressedSize).toFloat() / task.originalSize * 100).toInt()
                } else 0
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

        // Locate the file's position in the gallery timeline
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
