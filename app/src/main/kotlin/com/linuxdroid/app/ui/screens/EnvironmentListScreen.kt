package com.linuxdroid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.linuxdroid.app.ui.navigation.Screen
import com.linuxdroid.app.ui.viewmodel.EnvironmentViewModel
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.core.model.Architecture
import com.linuxdroid.core.model.Distribution
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.EnvironmentConfiguration
import com.linuxdroid.core.model.EnvironmentState
import com.linuxdroid.core.model.UsernameValidator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvironmentListScreen(
    navController: NavController,
    viewModel: EnvironmentViewModel = hiltViewModel(),
) {
    val environments by viewModel.environments.collectAsState()
    val installProgress by viewModel.installProgress.collectAsState()
    val installStatusText by viewModel.installStatusText.collectAsState()
    val installerLogs by viewModel.installerLogs.collectAsState()
    val neuColors = NeuTheme.colors

    var showCreateDialog by remember { mutableStateOf(false) }
    var envToDelete by remember { mutableStateOf<Environment?>(null) }
    var envForDesktop by remember { mutableStateOf<Environment?>(null) }
    var envForSettings by remember { mutableStateOf<Environment?>(null) }
    var envForStorage by remember { mutableStateOf<Environment?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.errorMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        containerColor = neuColors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Environments", color = neuColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = neuColors.background,
                    titleContentColor = neuColors.textPrimary,
                ),
                navigationIcon = {
                    NeuIconButton(
                        onClick = { navController.popBackStack() },
                        size = 38.dp,
                        tint = neuColors.textPrimary,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                    }
                },
                actions = {
                    NeuIconButton(
                        onClick = { navController.navigate(Screen.Diagnostics.route) },
                        size = 38.dp,
                        tint = neuColors.textPrimary,
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = "Diagnostics", modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                }
            )
        },
        floatingActionButton = {
            NeuButton(
                onClick = { showCreateDialog = true },
                isAccent = true,
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New Environment", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    ) { padding ->
        if (environments.isEmpty()) {
            EmptyEnvironmentsPlaceholder(
                modifier = Modifier
                    .fillMaxSize()
                    .background(neuColors.background)
                    .padding(padding),
                onCreateClick = { showCreateDialog = true }
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(neuColors.background)
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(environments, key = { it.id.value }) { env ->
                    val progress = installProgress[env.id.value]
                    val statusText = installStatusText[env.id.value]
                    val logs = installerLogs[env.id.value] ?: emptyList()

                    EnvironmentCard(
                        environment = env,
                        progress = progress,
                        statusText = statusText,
                        logs = logs,
                        onStartClick = { viewModel.startEnvironment(env) },
                        onStopClick = { viewModel.stopEnvironment(env) },
                        onRestartClick = { viewModel.restartEnvironment(env) },
                        onInstallClick = { viewModel.installRootfs(env) },
                        onShellClick = {
                            navController.navigate(Screen.Terminal.route(env.id.value))
                        },
                        onDesktopClick = { envForDesktop = env },
                        onSettingsClick = { envForSettings = env },
                        onStorageClick = { envForStorage = env },
                        onDiagnosticsClick = { navController.navigate(Screen.Diagnostics.route) },
                        onDeleteClick = { envToDelete = env },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateEnvironmentDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, dist, arch ->
                viewModel.createEnvironment(name, dist, arch, autoBootstrap = true)
                showCreateDialog = false
            }
        )
    }

    envToDelete?.let { env ->
        AlertDialog(
            onDismissRequest = { envToDelete = null },
            title = { Text("Delete Environment") },
            text = { Text("Are you sure you want to delete '${env.name}'?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteEnvironment(env)
                        envToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { envToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    envForDesktop?.let { env ->
        AlertDialog(
            onDismissRequest = { envForDesktop = null },
            title = { Text("Linux Desktop GUI") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The rootless Linux environment is running.")
                    Text(
                        "To start a graphical desktop (e.g. XFCE / Wayland), open the interactive shell and install a desktop environment:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        color = Color(0xFF1E1E1E),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "apt update && apt install -y xfce4",
                            color = Color(0xFF81C784),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val id = env.id.value
                    envForDesktop = null
                    navController.navigate(Screen.Terminal.route(id))
                }) {
                    Text("Open Shell")
                }
            },
            dismissButton = {
                TextButton(onClick = { envForDesktop = null }) {
                    Text("Close")
                }
            }
        )
    }

    envForSettings?.let { env ->
        EnvironmentSettingsDialog(
            environment = env,
            onDismiss = { envForSettings = null },
            onSave = { updatedConfig ->
                viewModel.updateConfiguration(env, updatedConfig)
                envForSettings = null
            }
        )
    }

    envForStorage?.let { env ->
        EnvironmentStorageDialog(
            environment = env,
            onDismiss = { envForStorage = null }
        )
    }
}

@Composable
private fun EmptyEnvironmentsPlaceholder(
    modifier: Modifier = Modifier,
    onCreateClick: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        NeuCard(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                NeuIconButton(
                    onClick = {},
                    enabled = false,
                    size = 72.dp,
                    tint = neuColors.primaryAccent,
                ) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Text(
                    text = "No Linux Environments",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = neuColors.textPrimary,
                )
                Text(
                    text = "Create a persistent Debian or Ubuntu Linux environment to run packages and terminal tools directly on your device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = neuColors.textSecondary,
                )
                Spacer(Modifier.height(4.dp))
                NeuButton(
                    onClick = onCreateClick,
                    isAccent = true,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Create Environment", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun EnvironmentCard(
    environment: Environment,
    progress: Float?,
    statusText: String?,
    logs: List<String> = emptyList(),
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onRestartClick: () -> Unit,
    onInstallClick: () -> Unit,
    onShellClick: () -> Unit,
    onDesktopClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onStorageClick: () -> Unit,
    onDiagnosticsClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    NeuCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = environment.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = neuColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${environment.distribution.displayName} • ${environment.architecture.abiName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = neuColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StateChip(state = environment.state)
                    NeuIconButton(
                        onClick = onDeleteClick,
                        size = 32.dp,
                        tint = neuColors.error,
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (environment.state == EnvironmentState.INSTALLING) {
                Spacer(modifier = Modifier.height(12.dp))
                if (progress != null && progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = neuColors.primaryAccent,
                        trackColor = neuColors.surfacePressed,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = neuColors.primaryAccent,
                        trackColor = neuColors.surfacePressed,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = statusText ?: "Downloading and setting up rootfs…",
                    style = MaterialTheme.typography.bodySmall,
                    color = neuColors.primaryAccent,
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Ubuntu installer style live terminal output
                InstallerTerminalConsole(logs = logs)
            }

            environment.failureMessage?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = neuColors.error.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error,
                        color = neuColors.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Primary State Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (environment.state) {
                    EnvironmentState.CREATED -> {
                        NeuButton(
                            onClick = onInstallClick,
                            isAccent = true,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("INSTALL", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    EnvironmentState.READY, EnvironmentState.STOPPED -> {
                        NeuButton(
                            onClick = onStartClick,
                            isAccent = true,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("START", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        NeuButton(
                            onClick = onShellClick,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("OPEN SHELL", fontSize = 12.sp)
                        }
                    }
                    EnvironmentState.RUNNING -> {
                        NeuButton(
                            onClick = onShellClick,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            isAccent = true,
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("OPEN SHELL", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        NeuButton(
                            onClick = onDesktopClick,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("OPEN DESKTOP", fontSize = 12.sp)
                        }
                        NeuButton(
                            onClick = onRestartClick,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("RESTART", fontSize = 12.sp)
                        }
                        NeuButton(
                            onClick = onStopClick,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(15.dp), tint = neuColors.error)
                            Spacer(Modifier.width(6.dp))
                            Text("STOP", fontSize = 12.sp, color = neuColors.error)
                        }
                    }
                    EnvironmentState.STARTING, EnvironmentState.STOPPING -> {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = neuColors.primaryAccent)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (environment.state == EnvironmentState.STARTING) "Starting…" else "Stopping…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = neuColors.textSecondary,
                        )
                    }
                    EnvironmentState.FAILED -> {
                        NeuButton(
                            onClick = onRestartClick,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            isAccent = true,
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("RETRY", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    else -> {}
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = neuColors.borderHighlight.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(6.dp))

            // Subsystem actions: Settings, Storage, Logs/Diagnostics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp), tint = neuColors.primaryAccent)
                    Spacer(Modifier.width(4.dp))
                    Text("Settings", fontSize = 12.sp, color = neuColors.textPrimary)
                }

                TextButton(onClick = onStorageClick) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(14.dp), tint = neuColors.primaryAccent)
                    Spacer(Modifier.width(4.dp))
                    Text("Storage", fontSize = 12.sp, color = neuColors.textPrimary)
                }

                TextButton(onClick = onDiagnosticsClick) {
                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(14.dp), tint = neuColors.primaryAccent)
                    Spacer(Modifier.width(4.dp))
                    Text("Diagnostics", fontSize = 12.sp, color = neuColors.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun StateChip(state: EnvironmentState) {
    val neuColors = NeuTheme.colors
    val (bgColor, textColor, label) = when (state) {
        EnvironmentState.RUNNING -> Triple(neuColors.success.copy(alpha = 0.15f), neuColors.success, "Running")
        EnvironmentState.STARTING -> Triple(neuColors.warning.copy(alpha = 0.15f), neuColors.warning, "Starting")
        EnvironmentState.STOPPING -> Triple(neuColors.warning.copy(alpha = 0.15f), neuColors.warning, "Stopping")
        EnvironmentState.INSTALLING -> Triple(neuColors.primaryAccent.copy(alpha = 0.15f), neuColors.primaryAccent, "Installing")
        EnvironmentState.FAILED -> Triple(neuColors.error.copy(alpha = 0.15f), neuColors.error, "Failed")
        EnvironmentState.READY -> Triple(neuColors.primaryAccent.copy(alpha = 0.15f), neuColors.primaryAccent, "Ready")
        EnvironmentState.STOPPED -> Triple(neuColors.surfacePressed, neuColors.textSecondary, "Stopped")
        EnvironmentState.CREATED -> Triple(neuColors.surfacePressed, neuColors.textSecondary, "Created")
        EnvironmentState.RECOVERING -> Triple(neuColors.warning.copy(alpha = 0.15f), neuColors.warning, "Recovering")
        EnvironmentState.DELETING -> Triple(neuColors.error.copy(alpha = 0.15f), neuColors.error, "Deleting")
        EnvironmentState.CLONING -> Triple(neuColors.secondaryAccent.copy(alpha = 0.15f), neuColors.secondaryAccent, "Cloning")
        EnvironmentState.RESETTING -> Triple(neuColors.warning.copy(alpha = 0.15f), neuColors.warning, "Resetting")
    }
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateEnvironmentDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, distribution: Distribution, architecture: Architecture) -> Unit,
) {
    var name by remember { mutableStateOf("Debian Linux") }
    var selectedDist by remember { mutableStateOf(Distribution.DEBIAN) }
    val detectedArch = remember { Architecture.current() }
    var selectedArch by remember { mutableStateOf(detectedArch) }
    val neuColors = NeuTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = neuColors.background,
        title = { Text("New Linux Environment", color = neuColors.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Environment Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = neuColors.textPrimary,
                        unfocusedTextColor = neuColors.textPrimary,
                        focusedBorderColor = neuColors.primaryAccent,
                        unfocusedBorderColor = neuColors.borderHighlight,
                        focusedLabelColor = neuColors.primaryAccent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Column {
                    Text("Distribution", style = MaterialTheme.typography.labelMedium, color = neuColors.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(Distribution.DEBIAN, Distribution.UBUNTU).forEach { dist ->
                            val isSelected = selectedDist == dist
                            NeuButton(
                                onClick = {
                                    selectedDist = dist
                                    if (name.isBlank() || name == "Debian Linux" || name == "Ubuntu Linux") {
                                        name = "${dist.displayName} Linux"
                                    }
                                },
                                isAccent = isSelected,
                                elevation = if (isSelected) 2.dp else 4.dp,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(dist.displayName, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }

                Column {
                    Text("Architecture", style = MaterialTheme.typography.labelMedium, color = neuColors.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(Architecture.ARM64).forEach { arch ->
                            val isSelected = selectedArch == arch
                            NeuButton(
                                onClick = { selectedArch = arch },
                                isAccent = isSelected,
                                elevation = if (isSelected) 2.dp else 4.dp,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(arch.abiName, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            NeuButton(
                onClick = { onCreate(name, selectedDist, selectedArch) },
                enabled = name.isNotBlank(),
                isAccent = true,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Create & Install", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            NeuButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun EnvironmentSettingsDialog(
    environment: Environment,
    onDismiss: () -> Unit,
    onSave: (EnvironmentConfiguration) -> Unit,
) {
    var linuxUser by remember { mutableStateOf(environment.configuration.linuxUser) }
    var homeDir by remember { mutableStateOf(environment.configuration.homeDir) }
    var sharedStorage by remember { mutableStateOf(environment.configuration.runtime.sharedStorageEnabled) }
    val userValidationError = remember(linuxUser) { UsernameValidator.validate(linuxUser) }
    val isFormValid = userValidationError == null
    val neuColors = NeuTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = neuColors.background,
        title = { Text("Environment Settings", color = neuColors.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = linuxUser,
                    onValueChange = { linuxUser = it },
                    label = { Text("Linux User") },
                    isError = userValidationError != null,
                    supportingText = userValidationError?.let {
                        { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = neuColors.textPrimary,
                        unfocusedTextColor = neuColors.textPrimary,
                        focusedBorderColor = neuColors.primaryAccent,
                        unfocusedBorderColor = neuColors.borderHighlight,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = homeDir,
                    onValueChange = { homeDir = it },
                    label = { Text("Home Directory") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = neuColors.textPrimary,
                        unfocusedTextColor = neuColors.textPrimary,
                        focusedBorderColor = neuColors.primaryAccent,
                        unfocusedBorderColor = neuColors.borderHighlight,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Enable Shared Storage", style = MaterialTheme.typography.bodyMedium, color = neuColors.textPrimary)
                    Switch(
                        checked = sharedStorage,
                        onCheckedChange = { sharedStorage = it }
                    )
                }
            }
        },
        confirmButton = {
            NeuButton(
                onClick = {
                    if (isFormValid) {
                        val sanitizedUser = linuxUser.trim().ifEmpty { "root" }
                        val sanitizedHome = UsernameValidator.sanitizeHomeDir(sanitizedUser, homeDir.trim().ifEmpty { "/root" })
                        val newConfig = environment.configuration.copy(
                            linuxUser = sanitizedUser,
                            homeDir = sanitizedHome,
                            runtime = environment.configuration.runtime.copy(
                                sharedStorageEnabled = sharedStorage
                            )
                        )
                        onSave(newConfig)
                    }
                },
                enabled = isFormValid,
                isAccent = true,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            NeuButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun EnvironmentStorageDialog(
    environment: Environment,
    onDismiss: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = neuColors.background,
        title = { Text("Storage & Shared Directory", color = neuColors.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Rootfs Location:", style = MaterialTheme.typography.labelMedium, color = neuColors.textSecondary)
                NeuCard(
                    modifier = Modifier.fillMaxWidth(),
                    isInset = true,
                ) {
                    Text(
                        text = environment.rootfsPath,
                        color = neuColors.textPrimary,
                        fontSize = 11.sp,
                        fontFamily = SfMono,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                Spacer(Modifier.height(4.dp))

                Text("Shared Android Directory:", style = MaterialTheme.typography.labelMedium, color = neuColors.textSecondary)
                NeuCard(
                    modifier = Modifier.fillMaxWidth(),
                    isInset = true,
                ) {
                    Text(
                        text = "/sdcard/LinuxDroid -> /home/user/Android",
                        color = neuColors.primaryAccent,
                        fontSize = 11.sp,
                        fontFamily = SfMono,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Text(
                    text = "Files placed in /sdcard/LinuxDroid on your device are safely accessible inside the Linux environment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = neuColors.textMuted
                )
            }
        },
        confirmButton = {
            NeuButton(
                onClick = onDismiss,
                isAccent = true,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("OK", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun InstallerTerminalConsole(
    logs: List<String>,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val neuColors = NeuTheme.colors

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    NeuCard(
        modifier = modifier.fillMaxWidth(),
        isInset = true,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column {
            // macOS Header toolbar
            MacosWindowHeader(
                title = "Installer Console",
                badgeText = "${logs.size} lines",
                subtitle = "rootfs",
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
                            contentDescription = "Copy installer logs",
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            )

            HorizontalDivider(
                color = neuColors.borderHighlight.copy(alpha = 0.2f),
                thickness = 0.5.dp
            )

            // Terminal log list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 220.dp)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (logs.isEmpty()) {
                    item {
                        Text(
                            text = ">>> Initializing bootstrap daemon...",
                            color = neuColors.textMuted,
                            fontFamily = SfMono,
                            fontSize = 11.sp
                        )
                    }
                } else {
                    items(logs) { line ->
                        val lineColor = if (neuColors.isDark) {
                            when {
                                line.contains("[PASS]") || line.contains("[OK]") || line.contains("[SUCCESS]") || line.contains("[READY]") -> Color(0xFF4ADE80)
                                line.contains("[ERROR]") || line.contains("[FATAL]") || line.contains("[VALIDATE_FAIL]") -> Color(0xFFF87171)
                                line.contains("[WARN]") -> Color(0xFFFBBF24)
                                line.contains("[DOWNLOAD]") || line.contains("[EXTRACT]") || line.contains("[INIT]") || line.contains("[SOURCE]") -> Color(0xFF38BDF8)
                                line.contains("[CONFIG]") || line.contains("[VERIFY]") || line.contains("[VALIDATE]") || line.contains("[PROMOTE]") -> Color(0xFFFDE047)
                                line.startsWith("extract:") -> neuColors.textMuted
                                else -> neuColors.textPrimary
                            }
                        } else {
                            when {
                                line.contains("[PASS]") || line.contains("[OK]") || line.contains("[SUCCESS]") || line.contains("[READY]") -> Color(0xFF15803D)
                                line.contains("[ERROR]") || line.contains("[FATAL]") || line.contains("[VALIDATE_FAIL]") -> Color(0xFFDC2626)
                                line.contains("[WARN]") -> Color(0xFFB45309)
                                line.contains("[DOWNLOAD]") || line.contains("[EXTRACT]") || line.contains("[INIT]") || line.contains("[SOURCE]") -> Color(0xFF1D4ED8)
                                line.contains("[CONFIG]") || line.contains("[VERIFY]") || line.contains("[VALIDATE]") || line.contains("[PROMOTE]") -> Color(0xFF7E22CE)
                                line.startsWith("extract:") -> neuColors.textMuted
                                else -> neuColors.textPrimary
                            }
                        }

                        Text(
                            text = line,
                            color = lineColor,
                            fontFamily = SfMono,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }

                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "▋",
                                color = neuColors.primaryAccent,
                                fontSize = 12.sp,
                                fontFamily = SfMono
                            )
                        }
                    }
                }
            }
        }
    }
}
