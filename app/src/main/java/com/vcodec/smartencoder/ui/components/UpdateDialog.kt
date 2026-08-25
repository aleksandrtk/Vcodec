package com.vcodec.smartencoder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vcodec.smartencoder.BuildConfig
import com.vcodec.smartencoder.ui.MainViewModel
import com.vcodec.smartencoder.ui.theme.AlertRed
import com.vcodec.smartencoder.ui.theme.LocalAppColors
import com.vcodec.smartencoder.ui.theme.PrimaryCyan
import com.vcodec.smartencoder.ui.theme.TextGray
import com.vcodec.smartencoder.ui.theme.TextWhite
import java.util.Locale

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
