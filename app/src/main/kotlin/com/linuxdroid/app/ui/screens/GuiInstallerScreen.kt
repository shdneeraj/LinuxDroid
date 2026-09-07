package com.linuxdroid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.linuxdroid.app.ui.navigation.Screen
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.app.ui.viewmodel.EnvironmentViewModel
import com.linuxdroid.core.model.GuiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuiInstallerScreen(
    environmentId: String,
    navController: NavController,
    viewModel: EnvironmentViewModel = hiltViewModel(),
) {
    val environments by viewModel.environments.collectAsState()
    val environment = environments.firstOrNull { it.id.value == environmentId }

    val guiStates by viewModel.guiStates.collectAsState()
    val guiInstallProgress by viewModel.guiInstallProgress.collectAsState()
    val guiInstallLogs by viewModel.guiInstallLogs.collectAsState()

    val currentGuiState = environment?.let { env ->
        guiStates[environmentId] ?: viewModel.getGuiState(env)
    } ?: GuiState.NOT_INSTALLED

    val progress = guiInstallProgress[environmentId] ?: 0f
    val logs = guiInstallLogs[environmentId] ?: emptyList()

    val neuColors = NeuTheme.colors
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "GUI Layer Manager",
                            style = MaterialTheme.typography.titleMedium,
                            color = neuColors.textPrimary,
                        )
                        environment?.let {
                            Text(
                                "${it.name} (${it.distribution.displayName})",
                                style = MaterialTheme.typography.bodySmall,
                                color = neuColors.textSecondary,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = neuColors.textPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = neuColors.surface,
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = neuColors.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Card
            NeuCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (currentGuiState) {
                            GuiState.INSTALLED -> {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Installed",
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(32.dp)
                                )
                                Column {
                                    Text(
                                        "Graphical Desktop Ready",
                                        fontWeight = FontWeight.Bold,
                                        color = neuColors.textPrimary,
                                        fontSize = 16.sp,
                                    )
                                    Text(
                                        "Wayland, Weston, LDDM, and LDDE are installed.",
                                        color = neuColors.textSecondary,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            GuiState.INSTALLING, GuiState.REPAIRING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = neuColors.primaryAccent,
                                    strokeWidth = 3.dp,
                                )
                                Column {
                                    Text(
                                        if (currentGuiState == GuiState.INSTALLING) "Installing GUI..." else "Repairing GUI...",
                                        fontWeight = FontWeight.Bold,
                                        color = neuColors.textPrimary,
                                        fontSize = 16.sp,
                                    )
                                    Text(
                                        "Setting up Wayland compositor and desktop layer",
                                        color = neuColors.textSecondary,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            GuiState.FAILED -> {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = "Failed",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(32.dp)
                                )
                                Column {
                                    Text(
                                        "GUI Installation Failed",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 16.sp,
                                    )
                                    Text(
                                        "CLI Linux environment remains fully operational.",
                                        color = neuColors.textSecondary,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            GuiState.NOT_INSTALLED -> {
                                Icon(
                                    Icons.Default.DesktopWindows,
                                    contentDescription = "Not Installed",
                                    tint = neuColors.primaryAccent,
                                    modifier = Modifier.size(32.dp)
                                )
                                Column {
                                    Text(
                                        "GUI Layer Not Installed",
                                        fontWeight = FontWeight.Bold,
                                        color = neuColors.textPrimary,
                                        fontSize = 16.sp,
                                    )
                                    Text(
                                        "Optional graphical desktop layer (Wayland, Weston, LDDM, LDDE).",
                                        color = neuColors.textSecondary,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }

                    if (currentGuiState == GuiState.INSTALLING || currentGuiState == GuiState.REPAIRING) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = neuColors.primaryAccent,
                            trackColor = neuColors.borderHighlight.copy(alpha = 0.3f),
                        )
                    }
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (currentGuiState) {
                    GuiState.NOT_INSTALLED -> {
                        Button(
                            onClick = { environment?.let { viewModel.installGui(it) } },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = neuColors.primaryAccent)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Install GUI")
                        }
                    }
                    GuiState.INSTALLED -> {
                        Button(
                            onClick = {
                                navController.navigate(Screen.Desktop.route(environmentId))
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) {
                            Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Launch Desktop")
                        }
                        OutlinedButton(
                            onClick = { environment?.let { viewModel.repairGui(it) } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Repair")
                        }
                    }
                    GuiState.FAILED -> {
                        Button(
                            onClick = { environment?.let { viewModel.installGui(it) } },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = neuColors.primaryAccent)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Retry Install")
                        }
                        OutlinedButton(
                            onClick = { environment?.let { viewModel.repairGui(it) } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Repair GUI")
                        }
                        OutlinedButton(
                            onClick = {
                                navController.navigate(Screen.Terminal.route(environmentId))
                            },
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("CLI")
                        }
                    }
                    GuiState.INSTALLING, GuiState.REPAIRING -> {
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Operation in progress…")
                        }
                    }
                }
            }

            // Terminal Console
            NeuCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                isInset = true,
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    MacosWindowHeader(
                        title = "GUI Install Console",
                        badgeText = "${logs.size} lines",
                        subtitle = "wayland-stack",
                        actions = {
                            NeuIconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(logs.joinToString("\n")))
                                },
                                size = 24.dp,
                                tint = neuColors.textSecondary,
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy logs",
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    )

                    HorizontalDivider(
                        color = neuColors.borderHighlight.copy(alpha = 0.2f),
                        thickness = 0.5.dp
                    )

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (logs.isEmpty()) {
                            item {
                                Text(
                                    text = ">>> Waiting for GUI installer to start...",
                                    color = neuColors.textMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                )
                            }
                        } else {
                            items(logs) { line ->
                                val color = when {
                                    line.contains("[SUCCESS]") -> Color(0xFF81C784)
                                    line.contains("[FAIL]") || line.contains("ERROR") -> Color(0xFFE57373)
                                    line.contains("[WARN]") -> Color(0xFFFFB74D)
                                    line.startsWith("[GUI_INSTALL]") -> Color(0xFF64B5F6)
                                    else -> neuColors.textSecondary
                                }
                                Text(
                                    text = line,
                                    color = color,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
