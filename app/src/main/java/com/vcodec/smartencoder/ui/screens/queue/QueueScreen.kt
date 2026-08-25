package com.vcodec.smartencoder.ui.screens.queue

import android.content.Context
import android.text.format.Formatter
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
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
import com.vcodec.smartencoder.data.TaskStatus
import com.vcodec.smartencoder.data.TranscodeTask
import com.vcodec.smartencoder.ui.MainViewModel
import com.vcodec.smartencoder.ui.components.format
import com.vcodec.smartencoder.ui.theme.AccentEmerald
import com.vcodec.smartencoder.ui.theme.AlertAmber
import com.vcodec.smartencoder.ui.theme.AlertRed
import com.vcodec.smartencoder.ui.theme.LocalAppColors
import com.vcodec.smartencoder.ui.theme.PrimaryCyan
import com.vcodec.smartencoder.ui.theme.TextGray
import com.vcodec.smartencoder.ui.theme.TextWhite
import java.util.Locale

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
fun ActiveTaskCard(task: TranscodeTask, context: Context, viewModel: MainViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.surfaceTransparent),
        border = BorderStroke(1.5.dp, PrimaryCyan.copy(alpha = 0.35f)),
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
fun QueueTaskItem(task: TranscodeTask, context: Context, viewModel: MainViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surfaceTransparent)
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
