package com.linuxdroid.app.ui.screens

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
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.app.ui.viewmodel.EnvironmentViewModel
import com.linuxdroid.app.ui.viewmodel.PackageManagerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageManagerScreen(
    environmentId: String,
    navController: NavController,
    envViewModel: EnvironmentViewModel = hiltViewModel(),
    pkgViewModel: PackageManagerViewModel = hiltViewModel(),
) {
    val environments by envViewModel.environments.collectAsState()
    val environment = environments.firstOrNull { it.id.value == environmentId }

    val packageLogs by pkgViewModel.packageLogs.collectAsState()
    val isOperating by pkgViewModel.isOperating.collectAsState()
    val operationStatus by pkgViewModel.operationStatus.collectAsState()
    val searchResults by pkgViewModel.searchResults.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var installPkgName by remember { mutableStateOf("") }
    var removePkgName by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }

    val neuColors = NeuTheme.colors
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()

    LaunchedEffect(packageLogs.size) {
        if (packageLogs.isNotEmpty()) {
            listState.animateScrollToItem(packageLogs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Package Manager",
                            style = MaterialTheme.typography.titleMedium,
                            color = neuColors.textPrimary,
                        )
                        environment?.let {
                            Text(
                                "${it.name} (${it.distribution.packageManager})",
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
        containerColor = neuColors.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status bar
            operationStatus?.let { status ->
                NeuCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isOperating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = neuColors.primaryAccent,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = neuColors.primaryAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = status,
                            color = neuColors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // Tabs: Install, Remove, Update
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = neuColors.surface,
                contentColor = neuColors.primaryAccent,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Install") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Remove") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Update") }
                )
            }

            // Tab Content
            when (selectedTab) {
                0 -> {
                    // Install Tab
                    NeuCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "Install Package",
                                fontWeight = FontWeight.Bold,
                                color = neuColors.textPrimary,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = installPkgName,
                                    onValueChange = { installPkgName = it },
                                    label = { Text("Package Name (e.g. htop, curl, git)") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                )
                                Button(
                                    onClick = {
                                        environment?.let { env ->
                                            pkgViewModel.installPackage(env, installPkgName)
                                            installPkgName = ""
                                        }
                                    },
                                    enabled = !isOperating && installPkgName.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = neuColors.primaryAccent)
                                ) {
                                    Text("Install")
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // Remove Tab
                    NeuCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "Remove Package",
                                fontWeight = FontWeight.Bold,
                                color = neuColors.textPrimary,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = removePkgName,
                                    onValueChange = { removePkgName = it },
                                    label = { Text("Package Name to Remove") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                )
                                Button(
                                    onClick = {
                                        environment?.let { env ->
                                            pkgViewModel.removePackage(env, removePkgName)
                                            removePkgName = ""
                                        }
                                    },
                                    enabled = !isOperating && removePkgName.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
                2 -> {
                    // Update Tab
                    NeuCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "Update System Packages",
                                fontWeight = FontWeight.Bold,
                                color = neuColors.textPrimary,
                            )
                            Text(
                                "Updates package indexes via ${environment?.distribution?.packageManager ?: "apt"} update.",
                                color = neuColors.textSecondary,
                                fontSize = 13.sp,
                            )
                            Button(
                                onClick = {
                                    environment?.let { pkgViewModel.updatePackages(it) }
                                },
                                enabled = !isOperating,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = neuColors.primaryAccent)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Update All Packages")
                            }
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
                        title = "Package Console",
                        badgeText = "${packageLogs.size} lines",
                        subtitle = environment?.distribution?.packageManager ?: "apt",
                        actions = {
                            NeuIconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(packageLogs.joinToString("\n")))
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
                            Spacer(Modifier.width(4.dp))
                            NeuIconButton(
                                onClick = { pkgViewModel.clearLogs() },
                                size = 24.dp,
                                tint = neuColors.textSecondary,
                            ) {
                                Icon(
                                    Icons.Default.ClearAll,
                                    contentDescription = "Clear logs",
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
                        if (packageLogs.isEmpty()) {
                            item {
                                Text(
                                    text = ">>> Package manager idle. Execute an install, remove, or update operation above.",
                                    color = neuColors.textMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                )
                            }
                        } else {
                            items(packageLogs) { line ->
                                val color = when {
                                    line.contains("[SUCCESS]") -> Color(0xFF81C784)
                                    line.contains("[FAIL]") || line.contains("error") -> Color(0xFFE57373)
                                    line.startsWith("[PACKAGE]") -> Color(0xFF64B5F6)
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
