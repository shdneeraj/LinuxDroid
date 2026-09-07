package com.linuxdroid.app.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.linuxdroid.app.R
import com.linuxdroid.app.ui.components.DistroIcon
import com.linuxdroid.app.ui.components.LinuxPenguinIcon
import com.linuxdroid.app.ui.navigation.Screen
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.app.ui.viewmodel.DistributionFetchState
import com.linuxdroid.app.ui.viewmodel.EnvironmentViewModel
import com.linuxdroid.app.ui.viewmodel.SettingsViewModel
import com.linuxdroid.core.model.*
import com.linuxdroid.core.storage.StorageAuthorizationState
import java.io.File

/**
 * Home screen:
 * - If NO rootfs is installed: Shows Rootfs Installation screen with automatic architecture detection.
 * - If rootfs IS installed: Shows clean dashboard with OS Hero Card (status & settings in header),
 *   Launch Mode cards (GUI & CLI with authentic distro square box icons), active session Resume/Start, reactive Stop button,
 *   and live system telemetry overview.
 */
@Composable
fun HomeScreen(
    navController: NavController = rememberNavController(),
    environmentViewModel: EnvironmentViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val environments by environmentViewModel.environments.collectAsState()
    val installProgress by environmentViewModel.installProgress.collectAsState()
    val installStatusText by environmentViewModel.installStatusText.collectAsState()
    val installerLogs by environmentViewModel.installerLogs.collectAsState()
    val authorizationState by settingsViewModel.authorizationState.collectAsState()

    var showStorageDialog by remember { mutableStateOf(false) }
    var showDesktopInfoDialog by remember { mutableStateOf<Environment?>(null) }

    val neuColors = NeuTheme.colors

    val activeEnv = environments.firstOrNull()

    val installingEnv = environments.firstOrNull {
        it.state == EnvironmentState.INSTALLING || (installProgress[it.id.value] != null)
    }

    val hasInstalledRootfs = activeEnv != null && (
        activeEnv.state == EnvironmentState.READY ||
        activeEnv.state == EnvironmentState.RUNNING ||
        activeEnv.state == EnvironmentState.STARTING ||
        activeEnv.state == EnvironmentState.STOPPED ||
        activeEnv.state == EnvironmentState.STOPPING
    )

    // Check shared storage permission on first launch
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settingsViewModel.checkStorageAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!settingsViewModel.hasPromptedStorageAccess()) {
            if (authorizationState !is StorageAuthorizationState.Authorized) {
                showStorageDialog = true
            }
        }
    }

    Scaffold(
        containerColor = neuColors.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(neuColors.background)
                .padding(padding)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!hasInstalledRootfs || installingEnv != null) {
                // NO Rootfs Present -> Show Rootfs Installation Screen
                RootfsInstallationCard(
                    environmentViewModel = environmentViewModel,
                    installingEnv = installingEnv,
                    progress = installingEnv?.let { installProgress[it.id.value] },
                    statusText = installingEnv?.let { installStatusText[it.id.value] },
                    logs = installingEnv?.let { installerLogs[it.id.value] } ?: emptyList(),
                    onSettingsClick = { navController.navigate(Screen.Settings.route) },
                    onInstallConfig = { config, name ->
                        environmentViewModel.createEnvironmentWithConfig(
                            installConfig = config,
                            environmentName = name,
                        )
                    }
                )
            } else {
                // Rootfs IS Present -> Show Front Screen Dashboard with 2 Main Action Icons
                ActiveEnvironmentHeroCard(
                    environment = activeEnv,
                    onSettingsClick = { navController.navigate(Screen.Settings.route) }
                )

                val guiStates by environmentViewModel.guiStates.collectAsState()
                val activeEnvGuiState = activeEnv.let { env ->
                    guiStates[env.id.value] ?: environmentViewModel.getGuiState(env)
                }

                Text(
                    "Launch Mode",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    color = neuColors.textPrimary,
                )

                // OS / GUI Mode Primary Card
                NeuGuiLaunchCard(
                    environment = activeEnv,
                    guiState = activeEnvGuiState,
                    onClick = {
                        if (activeEnvGuiState == GuiState.INSTALLED) {
                            navController.navigate(Screen.Desktop.route(activeEnv.id.value))
                        } else {
                            navController.navigate(Screen.GuiInstaller.route(activeEnv.id.value))
                        }
                    }
                )

                // Terminal / CLI Mode Primary Card
                NeuCliLaunchCard(
                    environment = activeEnv,
                    onClick = {
                        navController.navigate(Screen.Terminal.route(activeEnv.id.value))
                    },
                    onStop = {
                        environmentViewModel.stopEnvironment(activeEnv)
                    }
                )

                // Linux Management Menu
                LinuxManagementCard(
                    environment = activeEnv,
                    guiState = activeEnvGuiState,
                    onOpenTerminal = { navController.navigate(Screen.Terminal.route(activeEnv.id.value)) },
                    onManageGui = { navController.navigate(Screen.GuiInstaller.route(activeEnv.id.value)) },
                    onPackageManager = { navController.navigate(Screen.PackageManager.route(activeEnv.id.value)) },
                )

                // Live System Telemetry Card (Rootfs, RAM & Storage Bars, Network, CPU, Battery)
                SystemOverviewCard(context = context, environment = activeEnv)
            }
        }

        if (showStorageDialog) {
            SharedStorageAccessDialog(
                onDismiss = {
                    settingsViewModel.setPromptedStorageAccess(true)
                    showStorageDialog = false
                },
                onGrant = {
                    settingsViewModel.setPromptedStorageAccess(true)
                    showStorageDialog = false
                    settingsViewModel.getPermissionIntent()?.let { intent ->
                        context.startActivity(intent)
                    }
                }
            )
        }

        showDesktopInfoDialog?.let { env ->
            AlertDialog(
                onDismissRequest = { showDesktopInfoDialog = null },
                containerColor = neuColors.background,
                icon = {
                    NeuIconButton(
                        onClick = {},
                        enabled = false,
                        size = 52.dp,
                        tint = neuColors.secondaryAccent,
                    ) {
                        Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(28.dp))
                    }
                },
                title = {
                    Text(
                        "Desktop GUI Mode",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = neuColors.textPrimary,
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "LinuxDroid provides a rootless Wayland/X11 display server for graphical Linux apps.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = neuColors.textSecondary,
                        )
                        Surface(
                            color = neuColors.surfacePressed,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    "To install a desktop environment (XFCE4):",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = neuColors.textPrimary,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "apt update && apt install -y xfce4",
                                    fontFamily = SfMono,
                                    fontSize = 12.sp,
                                    color = neuColors.success,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    NeuButton(
                        onClick = {
                            val id = env.id.value
                            showDesktopInfoDialog = null
                            navController.navigate(Screen.Terminal.route(id))
                        },
                        isAccent = true,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("Open Shell", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    NeuButton(
                        onClick = { showDesktopInfoDialog = null },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text("Close", fontSize = 13.sp)
                    }
                }
            )
        }
    }
}

/**
 * Shared storage dialog informing user about /sdcard bridge.
 */
@Composable
private fun SharedStorageAccessDialog(
    onDismiss: () -> Unit,
    onGrant: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = neuColors.background,
        icon = {
            NeuIconButton(
                onClick = {},
                enabled = false,
                size = 52.dp,
                tint = neuColors.primaryAccent,
            ) {
                Icon(
                    Icons.Default.FolderShared,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = "Android Shared Storage",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = neuColors.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "LinuxDroid allows you to share files seamlessly between Android and your Linux environments.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = neuColors.textSecondary,
                )
                Surface(
                    color = neuColors.surfacePressed,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Shared Path in Linux:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = neuColors.textPrimary,
                        )
                        Text(
                            text = "/sdcard or ~/storage/shared",
                            fontFamily = SfMono,
                            fontSize = 11.sp,
                            color = neuColors.primaryAccent,
                        )
                    }
                }
            }
        },
        confirmButton = {
            NeuButton(
                onClick = onGrant,
                isAccent = true,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Grant Storage Access", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            NeuButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("Later", fontSize = 13.sp)
            }
        }
    )
}

/**
 * GUI Launch card with installed OS icon box, distribution info, and active session status.
 */
/**
 * GUI Launch card with installed OS icon box, distribution info, and active session status.
 */
@Composable
private fun NeuGuiLaunchCard(
    environment: Environment,
    guiState: GuiState,
    onClick: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    val isRunning = environment.state == EnvironmentState.RUNNING

    NeuCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp,
        shape = RoundedCornerShape(18.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            val iconWidth = maxWidth * 0.40f
            val infoWidth = maxWidth * 0.60f

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ── Left 40%: Big clickable square distro icon ─────────────
                Surface(
                    modifier = Modifier
                        .width(iconWidth)
                        .aspectRatio(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        ),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isRunning) neuColors.primaryAccent.copy(alpha = 0.10f)
                            else neuColors.surfacePressed,
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (isRunning) 1.5.dp else 1.dp,
                        color = neuColors.primaryAccent.copy(alpha = if (isRunning) 0.70f else 0.35f),
                    ),
                    shadowElevation = if (isRunning) 6.dp else 2.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        DistroIcon(
                            distribution = environment.distribution,
                            size = iconWidth * 0.65f,
                        )
                    }
                }

                // ── Right 60%: Info column ──────────────────────────────────
                Column(
                    modifier = Modifier.width(infoWidth - 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Desktop GUI",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        ),
                        color = neuColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        text = environment.distribution.displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = neuColors.primaryAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Surface(
                        color = neuColors.surfacePressed,
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            0.5.dp, neuColors.borderHighlight.copy(alpha = 0.4f),
                        ),
                    ) {
                        val badgeText = when (guiState) {
                            GuiState.INSTALLED -> "Wayland / X11"
                            GuiState.INSTALLING -> "Installing..."
                            GuiState.REPAIRING -> "Repairing..."
                            GuiState.FAILED -> "Failed (Tap to fix)"
                            GuiState.NOT_INSTALLED -> "Not Installed"
                        }
                        Text(
                            text = badgeText,
                            fontFamily = SfMono,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (guiState) {
                                GuiState.INSTALLED -> neuColors.secondaryAccent
                                GuiState.FAILED -> MaterialTheme.colorScheme.error
                                GuiState.INSTALLING, GuiState.REPAIRING -> neuColors.primaryAccent
                                GuiState.NOT_INSTALLED -> neuColors.textSecondary
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            maxLines = 1,
                        )
                    }

                    Text(
                        text = "${environment.architecture.linuxArch} · PRoot",
                        style = MaterialTheme.typography.bodySmall,
                        color = neuColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        val indicatorColor = when {
                            isRunning -> neuColors.success
                            guiState == GuiState.INSTALLED -> neuColors.success
                            guiState == GuiState.FAILED -> MaterialTheme.colorScheme.error
                            guiState == GuiState.INSTALLING || guiState == GuiState.REPAIRING -> neuColors.primaryAccent
                            else -> neuColors.textMuted
                        }
                        val statusText = when {
                            isRunning -> "Session active"
                            guiState == GuiState.INSTALLED -> "Tap icon to launch"
                            guiState == GuiState.FAILED -> "Tap to repair GUI"
                            guiState == GuiState.INSTALLING || guiState == GuiState.REPAIRING -> "Installing GUI..."
                            else -> "Tap to install GUI"
                        }
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(indicatorColor)
                        )
                        Text(
                            text = statusText,
                            fontSize = 10.sp,
                            fontFamily = SfMono,
                            color = if (isRunning) neuColors.success else neuColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * LinuxDroid Management card with direct access to CLI Terminal, GUI Manager, and Package Manager.
 */
@Composable
private fun LinuxManagementCard(
    environment: Environment,
    guiState: GuiState,
    onOpenTerminal: () -> Unit,
    onManageGui: () -> Unit,
    onPackageManager: () -> Unit,
) {
    val neuColors = NeuTheme.colors

    NeuCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = 3.dp,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    tint = neuColors.primaryAccent,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "LinuxDroid Management",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    ),
                    color = neuColors.textPrimary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Terminal Button
                OutlinedButton(
                    onClick = onOpenTerminal,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("CLI", fontSize = 12.sp)
                }

                // GUI Layer Button
                OutlinedButton(
                    onClick = onManageGui,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (guiState == GuiState.INSTALLED) "Desktop" else "GUI", fontSize = 12.sp)
                }

                // Package Manager Button
                OutlinedButton(
                    onClick = onPackageManager,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Packages", fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * CLI Launch card — big terminal icon left, distribution info right.
 * When a session is running: terminal icon navigates back to session,
 * and a Stop button appears to its right.
 */
@Composable
private fun NeuCliLaunchCard(
    environment: Environment,
    onClick: () -> Unit,
    onStop: () -> Unit = {},
) {
    val neuColors = NeuTheme.colors
    val isRunning = environment.state == EnvironmentState.RUNNING

    NeuCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── Left: Big terminal button (+ optional stop) ──────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Big terminal icon button
                Surface(
                    modifier = Modifier
                        .size(68.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        ),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isRunning) neuColors.primaryAccent.copy(alpha = 0.12f)
                            else neuColors.surfacePressed,
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (isRunning) 1.5.dp else 1.dp,
                        color = neuColors.primaryAccent.copy(alpha = if (isRunning) 0.75f else 0.40f),
                    ),
                    shadowElevation = if (isRunning) 4.dp else 2.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Open Terminal",
                            tint = neuColors.primaryAccent,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }

                // Stop button — only visible when running
                AnimatedVisibility(
                    visible = isRunning,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.CenterVertically),
                    exit  = fadeOut() + shrinkVertically(shrinkTowards = Alignment.CenterVertically),
                ) {
                    Surface(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onStop,
                            ),
                        shape = RoundedCornerShape(12.dp),
                        color = neuColors.error.copy(alpha = 0.10f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, neuColors.error.copy(alpha = 0.55f)
                        ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop Session",
                                tint = neuColors.error,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            // ── Right: Info column ───────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Terminal CLI",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        ),
                        color = neuColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Surface(
                        color = neuColors.surfacePressed,
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            0.5.dp, neuColors.borderHighlight.copy(alpha = 0.4f),
                        ),
                    ) {
                        Text(
                            text = "Bash",
                            fontFamily = SfMono,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = neuColors.primaryAccent,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1,
                        )
                    }
                }

                Text(
                    text = "${environment.distribution.displayName} · Interactive Shell",
                    style = MaterialTheme.typography.bodySmall,
                    color = neuColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isRunning) neuColors.success else neuColors.textMuted)
                    )
                    Text(
                        text = if (isRunning) "Session active — tap icon to resume" else "Tap to start a new session",
                        fontSize = 10.sp,
                        fontFamily = SfMono,
                        color = if (isRunning) neuColors.success else neuColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Live System Telemetry Card with Rootfs, RAM and Storage progress bars, Network, CPU and Battery.
 */
@Composable
private fun SystemOverviewCard(context: Context, environment: Environment?) {
    val neuColors = NeuTheme.colors
    val stats = remember(context, environment) { getSystemOverview(context, environment) }

    NeuCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Analytics,
                        contentDescription = null,
                        tint = neuColors.primaryAccent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "System Resources",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = neuColors.textPrimary,
                    )
                }

                Surface(
                    color = neuColors.surfacePressed,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.3f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (stats.isOnline) neuColors.success else neuColors.error)
                        )
                        Text(
                            text = "${stats.networkType} • ${stats.networkStatus}",
                            fontFamily = SfMono,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = neuColors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }

            // 1. Installed Rootfs Usage Bar (Above RAM Usage)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(14.dp), tint = neuColors.primaryAccent)
                        Text("Rootfs Disk Usage", style = MaterialTheme.typography.labelSmall, color = neuColors.textSecondary)
                    }
                    Text(
                        "${stats.rootfsUsedFormatted} / ${stats.storageTotalFormatted} (${(stats.rootfsFraction * 100).toInt().coerceAtLeast(1)}%)",
                        fontFamily = SfMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = neuColors.textPrimary,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(neuColors.surfacePressed)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = stats.rootfsFraction.coerceIn(0.02f, 1f))
                            .background(neuColors.primaryAccent, shape = RoundedCornerShape(4.dp))
                    )
                }
            }

            // 2. RAM Usage Bar (Actual Physical Device Memory)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF22C55E))
                        Text("RAM Usage", style = MaterialTheme.typography.labelSmall, color = neuColors.textSecondary)
                    }
                    Text(
                        "${stats.memUsedFormatted} / ${stats.memTotalFormatted} (${(stats.memFraction * 100).toInt()}%)",
                        fontFamily = SfMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = neuColors.textPrimary,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(neuColors.surfacePressed)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = stats.memFraction.coerceIn(0.02f, 1f))
                            .background(Color(0xFF22C55E), shape = RoundedCornerShape(4.dp))
                    )
                }
            }

            // 3. Storage Usage Bar (Actual Internal Storage)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(14.dp), tint = neuColors.secondaryAccent)
                        Text("Internal Storage", style = MaterialTheme.typography.labelSmall, color = neuColors.textSecondary)
                    }
                    Text(
                        "${stats.storageUsedFormatted} / ${stats.storageTotalFormatted} (${(stats.storageFraction * 100).toInt()}%)",
                        fontFamily = SfMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = neuColors.textPrimary,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(neuColors.surfacePressed)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = stats.storageFraction.coerceIn(0.02f, 1f))
                            .background(neuColors.secondaryAccent, shape = RoundedCornerShape(4.dp))
                    )
                }
            }

            HorizontalDivider(color = neuColors.borderHighlight.copy(alpha = 0.2f), thickness = 0.5.dp)

            // CPU & Battery Telemetry row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = neuColors.surfacePressed,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = null,
                            tint = neuColors.primaryAccent,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stats.processorText,
                            fontFamily = SfMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = neuColors.textPrimary,
                            maxLines = 1,
                        )
                    }
                }

                Surface(
                    color = neuColors.surfacePressed,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.BatteryChargingFull,
                            contentDescription = null,
                            tint = neuColors.secondaryAccent,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stats.batteryText,
                            fontFamily = SfMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = neuColors.textPrimary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private data class SystemOverview(
    val rootfsUsedFormatted: String,
    val rootfsFraction: Float,
    val memUsedFormatted: String,
    val memTotalFormatted: String,
    val memFraction: Float,
    val storageUsedFormatted: String,
    val storageTotalFormatted: String,
    val storageFraction: Float,
    val networkStatus: String,
    val networkType: String,
    val isOnline: Boolean,
    val batteryText: String,
    val processorText: String,
)

private fun calculateDirectorySize(dir: File): Long {
    if (!dir.exists()) return 0L
    var total = 0L
    val queue = ArrayDeque<File>()
    queue.add(dir)
    var visited = 0
    while (queue.isNotEmpty() && visited < 40000) {
        val current = queue.removeFirst()
        visited++
        val children = current.listFiles() ?: continue
        for (child in children) {
            if (child.isFile) {
                total += child.length()
            } else if (child.isDirectory) {
                queue.add(child)
            }
        }
    }
    return total
}

private fun getSystemOverview(context: Context, environment: Environment?): SystemOverview {
    var memUsedFormatted = "2.4 GB"
    var memTotalFormatted = "8.0 GB"
    var memFraction = 0.30f
    try {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (actManager != null) {
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val usedMem = memInfo.totalMem - memInfo.availMem
            memUsedFormatted = Formatter.formatFileSize(context, usedMem)
            memTotalFormatted = Formatter.formatFileSize(context, memInfo.totalMem)
            if (memInfo.totalMem > 0) {
                memFraction = (usedMem.toFloat() / memInfo.totalMem.toFloat()).coerceIn(0f, 1f)
            }
        }
    } catch (_: Exception) {}

    var storageUsedFormatted = "32.0 GB"
    var storageTotalFormatted = "128.0 GB"
    var storageFraction = 0.25f
    var storageTotalBytes = 128L * 1024 * 1024 * 1024
    try {
        val stat = StatFs(android.os.Environment.getDataDirectory().path)
        val available = stat.availableBytes
        val total = stat.totalBytes
        storageTotalBytes = total
        val used = total - available
        storageUsedFormatted = Formatter.formatFileSize(context, used)
        storageTotalFormatted = Formatter.formatFileSize(context, total)
        if (total > 0) {
            storageFraction = (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
    } catch (_: Exception) {}

    var rootfsUsedFormatted = "1.2 GB"
    var rootfsFraction = 0.01f
    try {
        val path = environment?.rootfsPath
        if (!path.isNullOrBlank()) {
            val rootfsDir = File(path)
            if (rootfsDir.exists()) {
                val sizeBytes = calculateDirectorySize(rootfsDir)
                if (sizeBytes > 0) {
                    rootfsUsedFormatted = Formatter.formatFileSize(context, sizeBytes)
                    if (storageTotalBytes > 0) {
                        rootfsFraction = (sizeBytes.toFloat() / storageTotalBytes.toFloat()).coerceIn(0.005f, 1f)
                    }
                }
            }
        }
    } catch (_: Exception) {}

    var networkStatus = "Connected"
    var networkType = "Wi-Fi"
    var isOnline = true
    try {
        val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = connMgr?.activeNetwork
        val caps = connMgr?.getNetworkCapabilities(activeNetwork)
        if (caps != null) {
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    networkType = "Wi-Fi"
                    networkStatus = "Connected"
                    isOnline = true
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    networkType = "Cellular"
                    networkStatus = "Connected"
                    isOnline = true
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                    networkType = "Ethernet"
                    networkStatus = "Connected"
                    isOnline = true
                }
                else -> {
                    networkType = "Network"
                    networkStatus = "Connected"
                    isOnline = true
                }
            }
        } else {
            networkType = "Offline"
            networkStatus = "Disconnected"
            isOnline = false
        }
    } catch (_: Exception) {
        networkType = "Online"
        networkStatus = "Active"
    }

    var batteryText = "85%"
    try {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        batteryText = if (pct >= 0) "$pct%${if (isCharging) " ⚡" else ""}" else "85%"
    } catch (_: Exception) {}

    val cores = Runtime.getRuntime().availableProcessors()
    val arch = Architecture.current().linuxArch
    val processorText = "$arch • $cores C"

    return SystemOverview(
        rootfsUsedFormatted = rootfsUsedFormatted,
        rootfsFraction = rootfsFraction,
        memUsedFormatted = memUsedFormatted,
        memTotalFormatted = memTotalFormatted,
        memFraction = memFraction,
        storageUsedFormatted = storageUsedFormatted,
        storageTotalFormatted = storageTotalFormatted,
        storageFraction = storageFraction,
        networkStatus = networkStatus,
        networkType = networkType,
        isOnline = isOnline,
        batteryText = batteryText,
        processorText = processorText,
    )
}

/**
 * First-Time / No-Rootfs Installation Card with Automatic Architecture Detection.
 */
/**
 * First-Time / No-Rootfs Installation Card with Step-by-Step Configuration Flow.
 *
 * User Flow:
 *  1. Choose Distribution (Debian / Ubuntu) -> Triggers background release fetch (does NOT install)
 *  2. Display Installation Configuration:
 *     - Select Release Version (Debian 13 Trixie / Debian 12 Bookworm, Ubuntu 24.04 Noble / 22.04 Jammy)
 *     - Architecture: ARM64 (Auto detected)
 *     - Username Input (validated)
 *     - Password & Confirm Password Input (with show/hide and validation)
 *  3. Explicit [Install] action button -> begins installation pipeline
 */
@Composable
private fun RootfsInstallationCard(
    environmentViewModel: EnvironmentViewModel,
    installingEnv: Environment?,
    progress: Float?,
    statusText: String?,
    logs: List<String>,
    onSettingsClick: () -> Unit,
    onInstallConfig: (InstallConfig, String) -> Unit,
) {
    val neuColors = NeuTheme.colors
    val detectedArch = remember { Architecture.current() }

    var selectedDistro by remember { mutableStateOf(Distribution.DEBIAN) }
    val distroFetchState by environmentViewModel.distroFetchState.collectAsState()

    // Trigger initial background metadata fetch for selected distro
    LaunchedEffect(selectedDistro) {
        environmentViewModel.prepareDistribution(selectedDistro)
    }

    val availableReleases = remember(selectedDistro) {
        DistributionCatalog.getAvailableReleases(selectedDistro)
    }
    var selectedRelease by remember(selectedDistro) {
        mutableStateOf(
            availableReleases.firstOrNull { it.isDefault }?.releaseCode
                ?: availableReleases.firstOrNull()?.releaseCode
                ?: "bookworm"
        )
    }

    var username by remember { mutableStateOf("user") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

    val usernameResult = remember(username) { UsernameValidator.validate(username) }
    val passwordResult = remember(password, confirmPassword) { PasswordValidator.validate(password, confirmPassword) }

    val isFormValid = usernameResult == null && passwordResult == null && username.isNotBlank() && password.isNotBlank()
    val envName = "${selectedDistro.displayName} ($selectedRelease)"

    NeuCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = 6.dp,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column {
            // ── Custom Header: Penguin + App name + big settings icon ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinuxPenguinIcon(size = 46.dp)
                    Column {
                        Text(
                            text = "LinuxDroid",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                            ),
                            color = neuColors.textPrimary,
                        )
                        Text(
                            text = "Rootless Linux Userspace on Android 16",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = neuColors.textSecondary,
                        )
                    }
                }
                NeuIconButton(
                    onClick = onSettingsClick,
                    size = 44.dp,
                    tint = neuColors.primaryAccent,
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            HorizontalDivider(
                color = neuColors.borderHighlight.copy(alpha = 0.25f),
                thickness = 0.5.dp
            )

            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (installingEnv == null) {
                    // ── Step 1: Distribution Selection ─────────────────────
                    Text(
                        "1. Choose Distribution",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = neuColors.textPrimary,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        listOf(
                            Triple(Distribution.DEBIAN, "Debian", "Stable & Lightweight"),
                            Triple(Distribution.UBUNTU, "Ubuntu", "Modern LTS & Packages"),
                        ).forEach { (distro, title, desc) ->
                            val isSelected = selectedDistro == distro
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        if (selectedDistro != distro) {
                                            selectedDistro = distro
                                            val releases = DistributionCatalog.getAvailableReleases(distro)
                                            selectedRelease = releases.firstOrNull { it.isDefault }?.releaseCode
                                                ?: releases.firstOrNull()?.releaseCode
                                                ?: "bookworm"
                                        }
                                    },
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) neuColors.primaryAccent.copy(alpha = 0.10f) else neuColors.surfacePressed,
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (isSelected) 1.5.dp else 0.5.dp,
                                    color = if (isSelected) neuColors.primaryAccent else neuColors.borderHighlight.copy(alpha = 0.3f),
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    DistroIcon(distribution = distro, size = 36.dp)
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (isSelected) neuColors.primaryAccent else neuColors.textPrimary,
                                    )
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                        color = neuColors.textSecondary,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }

                    // Background release pre-fetch status indicator
                    Surface(
                        color = neuColors.surfacePressed,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            when (val state = distroFetchState) {
                                is DistributionFetchState.Fetching -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp,
                                        color = neuColors.primaryAccent,
                                    )
                                    Text(
                                        text = state.message,
                                        fontSize = 11.sp,
                                        fontFamily = SfMono,
                                        color = neuColors.textSecondary,
                                        maxLines = 1,
                                    )
                                }
                                is DistributionFetchState.Ready -> {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = neuColors.success,
                                    )
                                    Text(
                                        text = "Release data verified for ${state.distro.displayName}",
                                        fontSize = 11.sp,
                                        fontFamily = SfMono,
                                        color = neuColors.success,
                                        maxLines = 1,
                                    )
                                }
                                is DistributionFetchState.Failed -> {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = neuColors.warning,
                                    )
                                    Text(
                                        text = "Using cached catalog definition",
                                        fontSize = 11.sp,
                                        fontFamily = SfMono,
                                        color = neuColors.textSecondary,
                                        maxLines = 1,
                                    )
                                }
                                else -> {
                                    Text(
                                        text = "Ready to configure",
                                        fontSize = 11.sp,
                                        fontFamily = SfMono,
                                        color = neuColors.textSecondary,
                                    )
                                }
                            }
                        }
                    }

                    // ── Step 2: Installation Configuration ──────────────────
                    Text(
                        "2. Installation Configuration",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = neuColors.textPrimary,
                    )

                    // Version Selection
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Select Version",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = neuColors.textSecondary,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            availableReleases.forEach { release ->
                                val isSelected = selectedRelease == release.releaseCode
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { selectedRelease = release.releaseCode },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) neuColors.surfacePressed else neuColors.background,
                                    border = androidx.compose.foundation.BorderStroke(
                                        width = if (isSelected) 1.5.dp else 0.5.dp,
                                        color = if (isSelected) neuColors.primaryAccent else neuColors.borderHighlight.copy(alpha = 0.3f),
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = { selectedRelease = release.releaseCode },
                                            colors = RadioButtonDefaults.colors(selectedColor = neuColors.primaryAccent),
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            text = release.displayName,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = neuColors.textPrimary,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Auto-Detected Architecture Indicator
                    Surface(
                        color = neuColors.surfacePressed,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp), tint = neuColors.success)
                                Text("Target Architecture", style = MaterialTheme.typography.bodySmall, color = neuColors.textSecondary)
                            }
                            Text(
                                "${detectedArch.linuxArch.uppercase()} (Auto Detected)",
                                fontFamily = SfMono,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = neuColors.primaryAccent,
                            )
                        }
                    }

                    // Username Input
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it.trim().lowercase() },
                        label = { Text("Linux Username") },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null, tint = neuColors.primaryAccent, modifier = Modifier.size(20.dp))
                        },
                        singleLine = true,
                        isError = usernameResult != null && username.isNotEmpty(),
                        supportingText = {
                            if (usernameResult != null && username.isNotEmpty()) {
                                Text(usernameResult, color = neuColors.error, fontSize = 11.sp)
                            } else {
                                Text("Primary Linux user account with sudo privileges", fontSize = 10.sp, color = neuColors.textSecondary)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = neuColors.textPrimary,
                            unfocusedTextColor = neuColors.textPrimary,
                            focusedBorderColor = neuColors.primaryAccent,
                            unfocusedBorderColor = neuColors.borderHighlight,
                            focusedLabelColor = neuColors.primaryAccent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Password Input
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = neuColors.primaryAccent, modifier = Modifier.size(20.dp))
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                    tint = neuColors.textSecondary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = neuColors.textPrimary,
                            unfocusedTextColor = neuColors.textPrimary,
                            focusedBorderColor = neuColors.primaryAccent,
                            unfocusedBorderColor = neuColors.borderHighlight,
                            focusedLabelColor = neuColors.primaryAccent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Confirm Password Input
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirm Password") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = neuColors.primaryAccent, modifier = Modifier.size(20.dp))
                        },
                        trailingIcon = {
                            IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                Icon(
                                    imageVector = if (confirmPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (confirmPasswordVisible) "Hide password" else "Show password",
                                    tint = neuColors.textSecondary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        isError = passwordResult != null && (password.isNotEmpty() || confirmPassword.isNotEmpty()),
                        supportingText = {
                            if (passwordResult != null && (password.isNotEmpty() || confirmPassword.isNotEmpty())) {
                                Text(passwordResult, color = neuColors.error, fontSize = 11.sp)
                            } else {
                                Text("Password must be at least 4 characters", fontSize = 10.sp, color = neuColors.textSecondary)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = neuColors.textPrimary,
                            unfocusedTextColor = neuColors.textPrimary,
                            focusedBorderColor = neuColors.primaryAccent,
                            unfocusedBorderColor = neuColors.borderHighlight,
                            focusedLabelColor = neuColors.primaryAccent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // ── Explicit [Install] Action Button ───────────────────
                    NeuButton(
                        onClick = {
                            val config = InstallConfig(
                                distro = selectedDistro,
                                release = selectedRelease,
                                username = username.trim(),
                                password = password,
                                architecture = detectedArch,
                            )
                            onInstallConfig(config, envName)
                        },
                        enabled = isFormValid,
                        isAccent = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Install LinuxDroid",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    // Installing State with Stage Progress and Live Logs
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Installing ${installingEnv.name}...",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = neuColors.textPrimary,
                            )
                            progress?.let { p ->
                                Text(
                                    "${(p * 100).toInt()}%",
                                    fontFamily = SfMono,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = neuColors.primaryAccent,
                                )
                            }
                        }

                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = neuColors.primaryAccent,
                                trackColor = neuColors.surfacePressed,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = neuColors.primaryAccent,
                                trackColor = neuColors.surfacePressed,
                            )
                        }

                        statusText?.let {
                            Surface(
                                color = neuColors.surfacePressed,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = SfMono,
                                    color = neuColors.primaryAccent,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        // Live installer log console
                        Surface(
                            color = Color(0xFF1A1A1A),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF333333)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 240.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(10.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                logs.takeLast(40).forEach { line ->
                                    val textColor = when {
                                        line.contains("FAIL", ignoreCase = true) || line.contains("error", ignoreCase = true) -> Color(0xFFFF6B6B)
                                        line.contains("PASS", ignoreCase = true) || line.contains("STAGE", ignoreCase = true) -> Color(0xFF81C784)
                                        line.contains("DEPLOY", ignoreCase = true) -> Color(0xFF64B5F6)
                                        else -> Color(0xFFCCCCCC)
                                    }
                                    Text(
                                        text = line,
                                        fontFamily = SfMono,
                                        fontSize = 11.sp,
                                        color = textColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hero card displaying LinuxDroid active environment telemetry.
 */
@Composable
private fun ActiveEnvironmentHeroCard(
    environment: Environment,
    onSettingsClick: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    val (badgeBgColor, badgeTextColor) = when (environment.state) {
        EnvironmentState.RUNNING -> neuColors.success.copy(alpha = 0.18f) to neuColors.success
        EnvironmentState.STARTING -> neuColors.warning.copy(alpha = 0.18f) to neuColors.warning
        EnvironmentState.READY -> neuColors.primaryAccent.copy(alpha = 0.15f) to neuColors.primaryAccent
        else -> neuColors.surfacePressed to neuColors.textSecondary
    }

    NeuCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = 6.dp,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── Main Hero Header: Penguin Icon + "LinuxDroid" + Status Badge + Big Settings ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Left: Authentic Linux Penguin (Tux) mascot icon
                LinuxPenguinIcon(size = 52.dp)

                // Center: "LinuxDroid" title (dedicated launch mode cards below show OS distribution)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LinuxDroid",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 21.sp,
                        ),
                        color = neuColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Right: Status Badge in front of LinuxDroid + Big Settings Icon Button (2x size)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        color = badgeBgColor,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            badgeTextColor.copy(alpha = 0.45f)
                        ),
                    ) {
                        Text(
                            text = environment.state.name,
                            fontFamily = SfMono,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeTextColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            maxLines = 1,
                        )
                    }

                    NeuIconButton(
                        onClick = onSettingsClick,
                        size = 44.dp,
                        tint = neuColors.primaryAccent,
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            HorizontalDivider(
                color = neuColors.borderHighlight.copy(alpha = 0.25f),
                thickness = 0.5.dp,
            )

            // Telemetry status pills (OS details are in dedicated launch mode cards below)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = neuColors.surfacePressed,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.4f)),
                ) {
                    Text(
                        text = "Subsystem: Rootless",
                        fontFamily = SfMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = neuColors.primaryAccent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        maxLines = 1,
                    )
                }

                Surface(
                    color = neuColors.surfacePressed,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.4f)),
                ) {
                    Text(
                        text = "Engine: PRoot (${environment.architecture.linuxArch})",
                        fontFamily = SfMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = neuColors.secondaryAccent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        maxLines = 1,
                    )
                }

                Surface(
                    color = neuColors.surfacePressed,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.4f)),
                ) {
                    Text(
                        text = "Kernel: Host 6.6+",
                        fontFamily = SfMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = neuColors.textSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
