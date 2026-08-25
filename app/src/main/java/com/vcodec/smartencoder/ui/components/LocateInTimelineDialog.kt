package com.vcodec.smartencoder.ui.components

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import com.vcodec.smartencoder.data.TranscodeTask
import com.vcodec.smartencoder.metadata.MetadataRestorer
import com.vcodec.smartencoder.ui.theme.AccentEmerald
import com.vcodec.smartencoder.ui.theme.DarkSurface
import com.vcodec.smartencoder.ui.theme.PrimaryCyan
import com.vcodec.smartencoder.ui.theme.TextGray
import com.vcodec.smartencoder.ui.theme.TextWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** A video entry in the gallery timeline, newest first. */
data class TimelineVideo(val id: Long, val name: String, val dateMs: Long)

/**
 * In-app "Where is this file?" view: shows the device's video timeline (MediaStore,
 * newest first) as a thumbnail grid, auto-scrolled to the given task's file with a
 * highlighted frame — so the user can instantly see its chronological position.
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
                    MetadataRestorer
                        .resolveToMediaStoreUri(context, uri)
                        ?.takeIf { it.authority == MediaStore.AUTHORITY }
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
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.Video.VideoColumns._ID,
                        MediaStore.Video.VideoColumns.DISPLAY_NAME,
                        MediaStore.Video.VideoColumns.DATE_MODIFIED
                    ),
                    null, null,
                    "${MediaStore.Video.VideoColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(MediaStore.Video.VideoColumns._ID)
                    val nameIdx = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DISPLAY_NAME)
                    val dateIdx = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DATE_MODIFIED)
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
                    targetPositionLabel ?: "Newest first • looking for ${task.fileName}",
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
                    val gridState = rememberLazyGridState()

                    // Auto-scroll so the highlighted target tile is at the top of the viewport
                    LaunchedEffect(list) {
                        if (targetIndex >= 0) {
                            targetPositionLabel =
                                "Position ${targetIndex + 1} of ${list.size} newest"
                            gridState.scrollToItem(targetIndex)
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(list.size) { index ->
                            val video = list[index]
                            val isTarget = index == targetIndex
                            val contentUri = ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video.id
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
                                        "↑ ${video.dateMs.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)) } ?: "?"}",
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
