package com.vcodec.smartencoder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vcodec.smartencoder.BuildConfig
import com.vcodec.smartencoder.ui.components.UpdateDialog
import com.vcodec.smartencoder.ui.screens.history.HistoryScreen
import com.vcodec.smartencoder.ui.screens.queue.QueueScreen
import com.vcodec.smartencoder.ui.screens.scanner.ScannerScreen
import com.vcodec.smartencoder.ui.theme.LocalAppColors
import com.vcodec.smartencoder.ui.theme.PrimaryCyan
import com.vcodec.smartencoder.ui.theme.TextGray
import com.vcodec.smartencoder.ui.theme.TextWhite

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
