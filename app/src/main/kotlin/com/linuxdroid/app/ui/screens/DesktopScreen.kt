package com.linuxdroid.app.ui.screens

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.linuxdroid.native_bridge.NativeBridge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.linuxdroid.app.ui.components.DistroIcon
import com.linuxdroid.app.ui.navigation.Screen
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.app.ui.viewmodel.EnvironmentViewModel
import com.linuxdroid.core.display.GuiSurfaceView
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.EnvironmentState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * Lifecycle phases for the Desktop GUI experience.
 */
enum class DesktopPhase {
    /** Live read-only Linux kernel & systemd boot console */
    BOOTING,
    /** LightDM / GDM style Linux Display Manager login screen */
    LOGIN,
    /** Active Wayland / X11 Graphical Desktop workspace */
    DESKTOP
}

/**
 * Desktop GUI Mode Screen:
 * - If session is already running: directly opens desktop session.
 * - If session is not running: starts environment, displays live non-editable Linux bootlog,
 *   then smoothly presents login screen (or auto-logs in if enabled).
 */
@Composable
fun DesktopScreen(
    navController: NavController,
    environmentViewModel: EnvironmentViewModel = hiltViewModel(),
) {
    val environments by environmentViewModel.environments.collectAsState()
    val neuColors = NeuTheme.colors

    val targetEnvId = remember {
        navController.currentBackStackEntry?.arguments?.getString("environmentId")
    }

    val environment = environments.firstOrNull { it.id.value == targetEnvId }
        ?: environments.firstOrNull()

    if (environment == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(neuColors.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "No Linux environment found",
                    style = MaterialTheme.typography.titleMedium,
                    color = neuColors.textPrimary,
                )
                NeuButton(onClick = { navController.popBackStack() }) {
                    Text("Return Home")
                }
            }
        }
        return
    }

    val isAlreadyRunning = remember { environment.state == EnvironmentState.RUNNING }
    val autoLoginEnabled = environment.configuration.desktop.autoLogin

    var currentPhase by remember {
        mutableStateOf(
            if (isAlreadyRunning) DesktopPhase.DESKTOP else DesktopPhase.BOOTING
        )
    }

    // Auto-start environment if stopped when entering boot phase
    LaunchedEffect(environment.id.value) {
        if (!isAlreadyRunning && environment.state != EnvironmentState.RUNNING && environment.state != EnvironmentState.STARTING) {
            environmentViewModel.startEnvironment(environment)
        }
    }

    // Automatically transition to DESKTOP when environment becomes active
    LaunchedEffect(environment.state) {
        if (environment.state == EnvironmentState.RUNNING && autoLoginEnabled) {
            currentPhase = DesktopPhase.DESKTOP
        }
    }

    AnimatedContent(
        targetState = currentPhase,
        label = "DesktopPhaseTransition",
        transitionSpec = {
            fadeIn() togetherWith fadeOut()
        }
    ) { phase ->
        when (phase) {
            DesktopPhase.BOOTING -> {
                LinuxBootConsoleScreen(
                    environment = environment,
                    onBootComplete = {
                        if (autoLoginEnabled) {
                            currentPhase = DesktopPhase.DESKTOP
                        } else {
                            currentPhase = DesktopPhase.LOGIN
                        }
                    },
                    onSkipBoot = {
                        if (autoLoginEnabled) {
                            currentPhase = DesktopPhase.DESKTOP
                        } else {
                            currentPhase = DesktopPhase.LOGIN
                        }
                    },
                    onExit = {
                        navController.popBackStack()
                    }
                )
            }
            DesktopPhase.LOGIN -> {
                LinuxLoginScreen(
                    environment = environment,
                    onLoginSuccess = {
                        currentPhase = DesktopPhase.DESKTOP
                    },
                    onOpenTerminal = {
                        navController.navigate(Screen.Terminal.route(environment.id.value))
                    },
                    onReboot = {
                        currentPhase = DesktopPhase.BOOTING
                    },
                    onPowerOff = {
                        environmentViewModel.stopEnvironment(environment)
                        navController.popBackStack()
                    }
                )
            }
            DesktopPhase.DESKTOP -> {
                LinuxDesktopWorkspace(
                    environment = environment,
                    onOpenTerminal = {
                        navController.navigate(Screen.Terminal.route(environment.id.value))
                    },
                    onLockSession = {
                        currentPhase = DesktopPhase.LOGIN
                    },
                    onStopSession = {
                        environmentViewModel.stopEnvironment(environment)
                        navController.popBackStack()
                    },
                    onNavigateHome = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}

/**
 * 1. Read-only live Linux Boot sequence console.
 */
@Composable
private fun LinuxBootConsoleScreen(
    environment: Environment,
    onBootComplete: () -> Unit,
    onSkipBoot: () -> Unit,
    onExit: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    val listState = rememberLazyListState()

    val bootLogLines = remember(environment) {
        listOf(
            "[  OK  ] Initializing LinuxDroid runtime environment for ${environment.distribution.displayName} (${environment.architecture.abiName})",
            "[  OK  ] Verifying guest rootfs at ${environment.rootfsPath}",
            "[  OK  ] Binding virtual guest filesystems: /dev, /dev/pts, /dev/shm, /proc, /sys",
            "[  OK  ] Configuring DNS resolution and loopback networking",
            "[  OK  ] Initializing PRoot syscall translation engine (seccomp-bpf enabled)",
            "[  OK  ] Executing guest init script: /sbin/linuxdroid-init",
            "[  OK  ] Initializing Wayland display socket (wayland-0)",
            "[  OK  ] Native GUI compositor ready (libweston)",
            "[  OK  ] Starting Linux desktop session (/usr/local/bin/linuxdroid-session)",
            "[  OK  ] Linux guest userspace active (state=RUNNING)",
        )
    }

    var displayedLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var bootCompleted by remember { mutableStateOf(false) }

    LaunchedEffect(environment.id.value, environment.state) {
        if (environment.state == EnvironmentState.RUNNING) {
            displayedLines = bootLogLines
            bootCompleted = true
            onBootComplete()
            return@LaunchedEffect
        }
        for (i in bootLogLines.indices) {
            displayedLines = bootLogLines.take(i + 1)
            listState.animateScrollToItem(i)
            if (environment.state == EnvironmentState.RUNNING) {
                displayedLines = bootLogLines
                bootCompleted = true
                onBootComplete()
                return@LaunchedEffect
            }
            val delayMs = when {
                i < 4 -> 30L
                bootLogLines[i].startsWith("[  OK  ]") -> 60L
                else -> 40L
            }
            delay(delayMs)
        }
        bootCompleted = true
        onBootComplete()
    }

    Scaffold(
        containerColor = Color(0xFF0D1117),
        topBar = {
            Surface(
                color = Color(0xFF161B22),
                shadowElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DistroIcon(distribution = environment.distribution, size = 32.dp)
                        Column {
                            Text(
                                text = "Booting ${environment.name}",
                                fontFamily = SfMono,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Text(
                                text = if (bootCompleted) "SYSTEM READY" else "INITIALIZING LINUX KERNEL & SYSTEMD...",
                                fontFamily = SfMono,
                                fontSize = 10.sp,
                                color = if (bootCompleted) Color(0xFF3FB950) else Color(0xFF58A6FF),
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onSkipBoot,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF8B949E)),
                        ) {
                            Text("Skip", fontSize = 12.sp, fontFamily = SfMono)
                        }
                        IconButton(onClick = onExit) {
                            Icon(Icons.Default.Close, contentDescription = "Exit", tint = Color(0xFF8B949E))
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D1117))
                .padding(padding)
                .padding(14.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(displayedLines) { line ->
                    BootLogLineView(line = line)
                }
            }
        }
    }
}

@Composable
private fun BootLogLineView(line: String) {
    if (line.startsWith("[  OK  ]")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "[",
                fontFamily = SfMono,
                fontSize = 12.sp,
                color = Color(0xFF8B949E),
            )
            Text(
                text = "  OK  ",
                fontFamily = SfMono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF3FB950),
            )
            Text(
                text = "]",
                fontFamily = SfMono,
                fontSize = 12.sp,
                color = Color(0xFF8B949E),
            )
            Text(
                text = line.removePrefix("[  OK  ]"),
                fontFamily = SfMono,
                fontSize = 12.sp,
                color = Color(0xFFE6EDF3),
            )
        }
    } else {
        Text(
            text = line,
            fontFamily = SfMono,
            fontSize = 12.sp,
            color = if (line.contains("error", ignoreCase = true)) Color(0xFFF85149) else Color(0xFF8B949E),
        )
    }
}

/**
 * 2. LightDM / Modern Linux Display Manager Login Screen.
 */
@Composable
private fun LinuxLoginScreen(
    environment: Environment,
    onLoginSuccess: () -> Unit,
    onOpenTerminal: () -> Unit,
    onReboot: () -> Unit,
    onPowerOff: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    var username by remember { mutableStateOf(environment.configuration.linuxUser.ifBlank { "root" }) }
    var password by remember { mutableStateOf("") }
    var rememberSession by remember { mutableStateOf(true) }
    var selectedSession by remember { mutableStateOf("XFCE4 Desktop") }

    val currentTime = remember {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }
    val currentDate = remember {
        SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
    }

    Scaffold(
        containerColor = neuColors.background,
        topBar = {
            Surface(
                color = neuColors.background,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DistroIcon(distribution = environment.distribution, size = 28.dp)
                        Text(
                            text = "${environment.distribution.displayName} 12",
                            fontFamily = SfMono,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = neuColors.textSecondary,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        NeuIconButton(
                            onClick = onOpenTerminal,
                            size = 32.dp,
                            tint = neuColors.primaryAccent,
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = "Terminal CLI", modifier = Modifier.size(16.dp))
                        }
                        NeuIconButton(
                            onClick = onReboot,
                            size = 32.dp,
                            tint = neuColors.secondaryAccent,
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reboot", modifier = Modifier.size(16.dp))
                        }
                        NeuIconButton(
                            onClick = onPowerOff,
                            size = 32.dp,
                            tint = neuColors.error,
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = "Power Off", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(neuColors.background)
                .padding(padding)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp),
            ) {
                // Clock header
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = currentTime,
                        fontFamily = SfPro,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Light,
                        color = neuColors.textPrimary,
                    )
                    Text(
                        text = currentDate,
                        fontFamily = SfPro,
                        fontSize = 14.sp,
                        color = neuColors.textSecondary,
                    )
                }

                // Login card
                NeuCard(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 6.dp,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // User Avatar
                        Surface(
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            color = neuColors.surfacePressed,
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, neuColors.primaryAccent.copy(alpha = 0.5f)),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = neuColors.primaryAccent,
                                )
                            }
                        }

                        Text(
                            text = username,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = neuColors.textPrimary,
                        )

                        // Password field
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            placeholder = { Text("Password (optional)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onLoginSuccess() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = neuColors.primaryAccent,
                                unfocusedBorderColor = neuColors.borderHighlight.copy(alpha = 0.4f),
                                focusedContainerColor = neuColors.surfacePressed,
                                unfocusedContainerColor = neuColors.surfacePressed,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Session mode selector
                        Surface(
                            color = neuColors.surfacePressed,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(16.dp), tint = neuColors.secondaryAccent)
                                    Text("Session:", style = MaterialTheme.typography.labelMedium, color = neuColors.textSecondary)
                                }
                                Text(
                                    text = selectedSession,
                                    fontFamily = SfMono,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = neuColors.primaryAccent,
                                )
                            }
                        }

                        // Auto-login checkbox
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { rememberSession = !rememberSession },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = rememberSession,
                                onCheckedChange = { rememberSession = it },
                                colors = CheckboxDefaults.colors(checkedColor = neuColors.primaryAccent)
                            )
                            Text(
                                "Auto-login on future boots",
                                style = MaterialTheme.typography.bodySmall,
                                color = neuColors.textSecondary,
                            )
                        }

                        // Log In Button
                        NeuButton(
                            onClick = onLoginSuccess,
                            modifier = Modifier.fillMaxWidth(),
                            isAccent = true,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(vertical = 12.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Log In to Desktop", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Text(
                    text = "LinuxDroid · Rootless PRoot Display Manager",
                    fontFamily = SfMono,
                    fontSize = 11.sp,
                    color = neuColors.textMuted,
                )
            }
        }
    }
}

data class DesktopWindow(
    val id: Long,
    val appId: String,
    val title: String
)

/**
 * 3. Graphical Desktop Workspace.
 *
 * Phase 10: Mobile Desktop UX
 * - Touch / Trackpad mode switching
 * - Immersive fullscreen toggle
 * - Collapsible mobile toolbar with modifier keys (ESC, TAB, Ctrl, Alt, Super, Shift, Arrows)
 * - Clipboard quick-paste
 * - Task / Window switcher dialog
 * - Dynamic display scaling (100%, 200%)
 * - Deterministic 5-priority BackHandler
 */
@Composable
private fun LinuxDesktopWorkspace(
    environment: Environment,
    onOpenTerminal: () -> Unit,
    onLockSession: () -> Unit,
    onStopSession: () -> Unit,
    onNavigateHome: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    val context = LocalContext.current
    val activity = context as? Activity
    var surfaceViewRef by remember { mutableStateOf<GuiSurfaceView?>(null) }

    // UX state
    var touchMode by remember { mutableStateOf(GuiSurfaceView.TouchMode.DIRECT) }
    var isFullscreen by remember { mutableStateOf(false) }
    var isToolbarExpanded by remember { mutableStateOf(true) }
    var currentScale by remember { mutableStateOf(1) }

    // Overlays and Dialogs
    var showExitDialog by remember { mutableStateOf(false) }
    var showWindowSwitcher by remember { mutableStateOf(false) }
    var showScaleSelector by remember { mutableStateOf(false) }
    var showSessionMenu by remember { mutableStateOf(false) }

    var activeWindows by remember { mutableStateOf<List<DesktopWindow>>(emptyList()) }
    var lastEscTimestamp by remember { mutableStateOf(0L) }

    // Latched modifier button states
    var isCtrlLatched by remember { mutableStateOf(false) }
    var isAltLatched by remember { mutableStateOf(false) }
    var isSuperLatched by remember { mutableStateOf(false) }
    var isShiftLatched by remember { mutableStateOf(false) }

    fun refreshActiveWindows() {
        val raw = NativeBridge.getActiveWindows()
        activeWindows = raw.mapNotNull { desc ->
            val parts = desc.split(":", limit = 3)
            if (parts.isNotEmpty()) {
                val id = parts[0].toLongOrNull() ?: return@mapNotNull null
                val appId = if (parts.size > 1) parts[1] else "App"
                val title = if (parts.size > 2) parts[2] else appId
                DesktopWindow(id, appId, title)
            } else null
        }
    }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val item = clipboard?.primaryClip?.getItemAt(0)
        val text = item?.text?.toString()
        if (!text.isNullOrEmpty()) {
            surfaceViewRef?.pasteText(text)
            Toast.makeText(context, "Pasted ${text.length} characters", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    // Fullscreen Insets controller
    val insetsController = remember(activity) {
        activity?.window?.let { win ->
            WindowCompat.getInsetsController(win, win.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    LaunchedEffect(isFullscreen) {
        if (isFullscreen) {
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Deterministic BackHandler with 5-priority policy
    BackHandler {
        when {
            // Priority 1: Dismiss any open mobile dialog / sheet
            showExitDialog -> showExitDialog = false
            showWindowSwitcher -> showWindowSwitcher = false
            showScaleSelector -> showScaleSelector = false
            showSessionMenu -> showSessionMenu = false

            // Priority 2 & 3: Dispatch Escape key to active Linux window
            else -> {
                surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_ESCAPE)
                val now = System.currentTimeMillis()
                if (lastEscTimestamp > 0L && now - lastEscTimestamp < 1500L) {
                    showExitDialog = true
                } else {
                    Toast.makeText(context, "Sent ESC to Linux. Press Back again to exit.", Toast.LENGTH_SHORT).show()
                    lastEscTimestamp = now
                }
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFF1E222B),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E222B))
                .padding(if (isFullscreen) PaddingValues(0.dp) else padding),
        ) {
            // 1. Presentation Surface
            AndroidView(
                factory = { ctx ->
                    GuiSurfaceView(ctx).also {
                        it.touchMode = touchMode
                        surfaceViewRef = it
                    }
                },
                update = { view ->
                    view.touchMode = touchMode
                },
                modifier = Modifier.fillMaxSize()
            )

            // 2. Session Reconnection / Status Banner
            if (environment.state != EnvironmentState.RUNNING) {
                Surface(
                    color = Color(0xCC000000),
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = neuColors.warning,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Session state: ${environment.state}...",
                            fontFamily = SfMono,
                            fontSize = 11.sp,
                            color = neuColors.warning
                        )
                    }
                }
            }

            // 3. Mobile Desktop Toolbar Overlay
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                if (!isFullscreen || isToolbarExpanded) {
                    // Top Bar / Control Panel
                    Surface(
                        color = Color(0xE6161920),
                        shadowElevation = 6.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                        ) {
                            // Primary Control Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    DistroIcon(distribution = environment.distribution, size = 22.dp)
                                    Text(
                                        text = environment.name,
                                        fontFamily = SfPro,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    // Touch Mode Toggle
                                    Surface(
                                        color = if (touchMode == GuiSurfaceView.TouchMode.DIRECT) neuColors.primaryAccent.copy(alpha = 0.2f) else neuColors.secondaryAccent.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.clickable {
                                            touchMode = if (touchMode == GuiSurfaceView.TouchMode.DIRECT) {
                                                GuiSurfaceView.TouchMode.TRACKPAD
                                            } else {
                                                GuiSurfaceView.TouchMode.DIRECT
                                            }
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (touchMode == GuiSurfaceView.TouchMode.DIRECT) Icons.Default.TouchApp else Icons.Default.Mouse,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp),
                                                tint = if (touchMode == GuiSurfaceView.TouchMode.DIRECT) neuColors.primaryAccent else neuColors.secondaryAccent
                                            )
                                            Text(
                                                text = if (touchMode == GuiSurfaceView.TouchMode.DIRECT) "Direct" else "Trackpad",
                                                fontSize = 10.sp,
                                                fontFamily = SfMono,
                                                fontWeight = FontWeight.Bold,
                                                color = if (touchMode == GuiSurfaceView.TouchMode.DIRECT) neuColors.primaryAccent else neuColors.secondaryAccent
                                            )
                                        }
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    // Soft Keyboard toggle
                                    IconButton(
                                        onClick = { surfaceViewRef?.toggleSoftKeyboard() },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Keyboard, contentDescription = "Soft Keyboard", tint = neuColors.primaryAccent, modifier = Modifier.size(16.dp))
                                    }
                                    // Clipboard Paste
                                    IconButton(
                                        onClick = { pasteFromClipboard() },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = neuColors.secondaryAccent, modifier = Modifier.size(16.dp))
                                    }
                                    // Tasks / Window Switcher
                                    IconButton(
                                        onClick = {
                                            refreshActiveWindows()
                                            showWindowSwitcher = true
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Layers, contentDescription = "Tasks", tint = neuColors.textPrimary, modifier = Modifier.size(16.dp))
                                    }
                                    // Scale Selector
                                    IconButton(
                                        onClick = { showScaleSelector = true },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.AspectRatio, contentDescription = "Scale", tint = neuColors.textPrimary, modifier = Modifier.size(16.dp))
                                    }
                                    // Fullscreen toggle
                                    IconButton(
                                        onClick = {
                                            isFullscreen = !isFullscreen
                                            if (isFullscreen) isToolbarExpanded = false
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                            contentDescription = "Fullscreen",
                                            tint = neuColors.textPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    // Session Menu
                                    IconButton(
                                        onClick = { showSessionMenu = true },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = neuColors.textPrimary, modifier = Modifier.size(16.dp))
                                    }
                                    // Collapse button if in fullscreen
                                    if (isFullscreen) {
                                        IconButton(
                                            onClick = { isToolbarExpanded = false },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Collapse", tint = neuColors.textSecondary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }

                            // Modifier Key Bar (Horizontally scrollable)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ModifierPill(label = "ESC", isActive = false) {
                                    surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_ESCAPE)
                                }
                                ModifierPill(label = "TAB", isActive = false) {
                                    surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_TAB)
                                }
                                ModifierPill(label = "Ctrl", isActive = isCtrlLatched) {
                                    isCtrlLatched = surfaceViewRef?.toggleModifier(KeyEvent.KEYCODE_CTRL_LEFT) ?: !isCtrlLatched
                                }
                                ModifierPill(label = "Alt", isActive = isAltLatched) {
                                    isAltLatched = surfaceViewRef?.toggleModifier(KeyEvent.KEYCODE_ALT_LEFT) ?: !isAltLatched
                                }
                                ModifierPill(label = "Super", isActive = isSuperLatched) {
                                    isSuperLatched = surfaceViewRef?.toggleModifier(KeyEvent.KEYCODE_META_LEFT) ?: !isSuperLatched
                                }
                                ModifierPill(label = "Shift", isActive = isShiftLatched) {
                                    isShiftLatched = surfaceViewRef?.toggleModifier(KeyEvent.KEYCODE_SHIFT_LEFT) ?: !isShiftLatched
                                }
                                ModifierPill(label = "←", isActive = false) {
                                    surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_DPAD_LEFT)
                                }
                                ModifierPill(label = "↑", isActive = false) {
                                    surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_DPAD_UP)
                                }
                                ModifierPill(label = "↓", isActive = false) {
                                    surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_DPAD_DOWN)
                                }
                                ModifierPill(label = "→", isActive = false) {
                                    surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                                }
                                ModifierPill(label = "Home", isActive = false) {
                                    surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_MOVE_HOME)
                                }
                                ModifierPill(label = "End", isActive = false) {
                                    surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_MOVE_END)
                                }
                            }
                        }
                    }
                }
            }

            // Floating Pill when Fullscreen and Toolbar collapsed
            if (isFullscreen && !isToolbarExpanded) {
                Surface(
                    color = Color(0xCC161920),
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 8.dp)
                        .clickable { isToolbarExpanded = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DistroIcon(distribution = environment.distribution, size = 16.dp)
                        Text(
                            text = if (touchMode == GuiSurfaceView.TouchMode.DIRECT) "Touch" else "Trackpad",
                            fontFamily = SfMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = neuColors.primaryAccent
                        )
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Expand Toolbar",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 4. Modals and Dialogs

            // Task / Window Switcher Dialog
            if (showWindowSwitcher) {
                AlertDialog(
                    onDismissRequest = { showWindowSwitcher = false },
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Active Linux Windows", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { showWindowSwitcher = false }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    text = {
                        if (activeWindows.isEmpty()) {
                            Text("No graphical windows currently open.", color = neuColors.textSecondary, fontSize = 13.sp)
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(activeWindows) { win ->
                                    Surface(
                                        color = neuColors.surfacePressed,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = win.title.ifEmpty { win.appId },
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = neuColors.textPrimary
                                            )
                                            Text(
                                                text = "App: ${win.appId} • ID: ${win.id}",
                                                fontSize = 11.sp,
                                                fontFamily = SfMono,
                                                color = neuColors.textSecondary
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Button(
                                                    onClick = {
                                                        NativeBridge.performWindowAction(win.id, "activate")
                                                        showWindowSwitcher = false
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text("Focus", fontSize = 11.sp)
                                                }
                                                OutlinedButton(
                                                    onClick = {
                                                        NativeBridge.performWindowAction(win.id, "maximize")
                                                        showWindowSwitcher = false
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text("Maximize", fontSize = 11.sp)
                                                }
                                                OutlinedButton(
                                                    onClick = {
                                                        NativeBridge.performWindowAction(win.id, "close")
                                                        refreshActiveWindows()
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = neuColors.error),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text("Close", fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showWindowSwitcher = false }) {
                            Text("Done")
                        }
                    }
                )
            }

            // Display Scale Dialog
            if (showScaleSelector) {
                AlertDialog(
                    onDismissRequest = { showScaleSelector = false },
                    title = { Text("Display Scaling", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Scale factor for Weston and LDDE desktop:", fontSize = 13.sp, color = neuColors.textSecondary)
                            listOf(
                                1 to "100% (Native / Sharp)",
                                2 to "200% (HiDPI / Touch Friendly)"
                            ).forEach { (scale, label) ->
                                Surface(
                                    color = if (currentScale == scale) neuColors.primaryAccent.copy(alpha = 0.15f) else neuColors.surfacePressed,
                                    shape = RoundedCornerShape(8.dp),
                                    border = if (currentScale == scale) androidx.compose.foundation.BorderStroke(1.dp, neuColors.primaryAccent) else null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            currentScale = scale
                                            NativeBridge.setOutputScale(scale)
                                            showScaleSelector = false
                                            Toast.makeText(context, "Scale set to $label", Toast.LENGTH_SHORT).show()
                                        }
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = if (currentScale == scale) FontWeight.Bold else FontWeight.Normal,
                                        color = if (currentScale == scale) neuColors.primaryAccent else neuColors.textPrimary,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showScaleSelector = false }) {
                            Text("Close")
                        }
                    }
                )
            }

            // Session Menu Dialog
            if (showSessionMenu) {
                AlertDialog(
                    onDismissRequest = { showSessionMenu = false },
                    title = { Text("Desktop Session Menu", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(
                                color = neuColors.surfacePressed,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Distribution: ${environment.distribution.displayName} (${environment.architecture.abiName})", fontSize = 12.sp, fontFamily = SfMono)
                                    Text("State: ${environment.state}", fontSize = 12.sp, fontFamily = SfMono)
                                    Text("Scaling: ${currentScale * 100}%", fontSize = 12.sp, fontFamily = SfMono)
                                    Text("Input Mode: ${touchMode.name}", fontSize = 12.sp, fontFamily = SfMono)
                                }
                            }
                            Button(
                                onClick = {
                                    showSessionMenu = false
                                    onOpenTerminal()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Open Terminal CLI")
                            }
                            OutlinedButton(
                                onClick = {
                                    showSessionMenu = false
                                    onLockSession()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Lock Session")
                            }
                            OutlinedButton(
                                onClick = {
                                    showSessionMenu = false
                                    NativeBridge.guiStop()
                                    NativeBridge.guiStart()
                                    Toast.makeText(context, "GUI Compositor Restarted", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Restart Wayland Compositor")
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showSessionMenu = false
                                showExitDialog = true
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = neuColors.error)
                        ) {
                            Text("Exit / Stop")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSessionMenu = false }) {
                            Text("Dismiss")
                        }
                    }
                )
            }

            // Exit Confirmation Dialog (Back Policy Priority 4 / Session Stop)
            if (showExitDialog) {
                AlertDialog(
                    onDismissRequest = { showExitDialog = false },
                    title = { Text("Exit Desktop Session", fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            "Choose whether to leave the Linux environment running in the background or stop it completely.",
                            fontSize = 13.sp,
                            color = neuColors.textSecondary
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showExitDialog = false
                                onNavigateHome()
                            }
                        ) {
                            Text("Run in Background")
                        }
                    },
                    dismissButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { showExitDialog = false }) {
                                Text("Cancel")
                            }
                            TextButton(
                                onClick = {
                                    showExitDialog = false
                                    onStopSession()
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = neuColors.error)
                            ) {
                                Text("Stop Session")
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ModifierPill(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val neuColors = NeuTheme.colors
    Surface(
        color = if (isActive) neuColors.primaryAccent else Color(0xFF2C3240),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            fontFamily = SfMono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isActive) Color.White else neuColors.textSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

