package dev.leonardo.ocbeacon.ui.screens.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.data.repository.LocalServerManager
import dev.leonardo.ocbeacon.ui.components.indicators.PulsingDotsIndicator
import dev.leonardo.ocbeacon.ui.screens.home.components.*

/**
 * Home Screen - Server list and management
 * 
 * Each server card has Connect/Disconnect/Sessions buttons.
 * Multiple servers can be connected simultaneously.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateToSessions: (serverUrl: String, username: String, password: String, serverName: String, serverId: String) -> Unit = { _, _, _, _, _ -> },
    onNavigateToServerSettings: (serverUrl: String, username: String, password: String, serverName: String, serverId: String) -> Unit = { _, _, _, _, _ -> },
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    viewModel: HomeViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Track battery optimization status, re-check when app resumes
    var isBatteryOptimized by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                isBatteryOptimized = !pm.isIgnoringBatteryOptimizations(context.packageName)
                viewModel.refreshLocalRuntimeState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // We need to track which server requested notification permission so we
    // can resume the connect flow after the permission dialog.
    var pendingConnectServerId by remember { mutableStateOf<String?>(null) }
    var pendingLocalStart by remember { mutableStateOf(false) }
    var showLocalLaunchOptionsDialog by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Whether granted or denied, proceed with connection
        pendingConnectServerId?.let { viewModel.connectToServer(it) }
        pendingConnectServerId = null
    }

    val runCommandPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingLocalStart) {
            viewModel.startLocalServer(context)
        } else if (!granted) {
            Toast.makeText(context, R.string.home_local_permission_required, Toast.LENGTH_LONG).show()
        }
        pendingLocalStart = false
    }

    fun requestNotificationPermissionAndConnect(serverId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pendingConnectServerId = serverId
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.connectToServer(serverId)
        }
    }

    fun requestRunCommandPermissionAndStartLocal() {
        val permissionState = ContextCompat.checkSelfPermission(
            context,
            "com.termux.permission.RUN_COMMAND",
        )
        if (permissionState == PackageManager.PERMISSION_GRANTED) {
            viewModel.startLocalServer(context)
            return
        }

        pendingLocalStart = true
        runCommandPermissionLauncher.launch("com.termux.permission.RUN_COMMAND")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = { viewModel.showAddServerDialog() }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.home_add_server))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                    IconButton(onClick = onNavigateToAbout) {
                        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.about_title))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    PulsingDotsIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        dotSize = 12.dp,
                        dotSpacing = 8.dp
                    )
                }
                else -> {
                    val localServer = uiState.servers.firstOrNull { it.url == LocalServerManager.LOCAL_SERVER_URL }
                    val remoteServers = uiState.servers.filterNot { it.url == LocalServerManager.LOCAL_SERVER_URL }
                    val useGrid = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

                    if (useGrid) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(280.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Battery optimization warning banner
                            if (isBatteryOptimized) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "__battery_banner") {
                                    BatteryOptimizationBanner(
                                        onDisable = {
                                            val intent = Intent(
                                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        }
                                    )
                                }
                            }

                            if (uiState.showLocalRuntime) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "__local_runtime") {
                                    LocalRuntimeCard(
                                        termuxInstalled = uiState.termuxInstalled,
                                        runtimeStatus = uiState.localRuntimeStatus,
                                        statusMessage = uiState.localRuntimeMessage,
                                        fixCommand = uiState.localRuntimeFixCommand,
                                        needsOverlaySettings = uiState.localRuntimeNeedsOverlaySettings,
                                        localServerConnected = localServer?.id in uiState.connectedServerIds,
                                        localServerConnecting = localServer?.id in uiState.connectingServerIds,
                                        localServerConnectionError = localServer?.id?.let { uiState.connectionErrors[it] },
                                        showLocalServerSettings = localServer?.id in uiState.serverSettingsReadyIds,
                                        onStart = { requestRunCommandPermissionAndStartLocal() },
                                        onStop = { viewModel.stopLocalServer(context) },
                                        onSetup = {
                                            val setupCommand = uiState.setupCommand ?: viewModel.getLocalSetupCommand()
                                            clipboardManager.setText(AnnotatedString(setupCommand))
                                            Toast.makeText(context, R.string.home_local_setup_copied, Toast.LENGTH_SHORT).show()
                                            viewModel.setupLocalServer(context)
                                        },
                                        onCopyFixCommand = { command ->
                                            clipboardManager.setText(AnnotatedString(command))
                                            Toast.makeText(context, R.string.home_local_fix_command_copied, Toast.LENGTH_SHORT).show()
                                        },
                                        onOpenTermuxOverlaySettings = {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:com.termux"),
                                            )
                                            context.startActivity(intent)
                                        },
                                        onOpenLocalSessions = {
                                            localServer?.let { server ->
                                                onNavigateToSessions(
                                                    server.url,
                                                    server.username,
                                                    server.password ?: "",
                                                    server.displayName,
                                                    server.id,
                                                )
                                            }
                                        },
                                        onOpenLocalServerSettings = {
                                            localServer?.let { server ->
                                                onNavigateToServerSettings(
                                                    server.url,
                                                    server.username,
                                                    server.password ?: "",
                                                    server.displayName,
                                                    server.id,
                                                )
                                            }
                                        },
                                        onOpenLocalLaunchOptions = {
                                            showLocalLaunchOptionsDialog = true
                                        },
                                        onInstallTermux = {
                                            val intent = Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://f-droid.org/packages/com.termux/")
                                            )
                                            context.startActivity(intent)
                                        },
                                    )
                                }
                            }

                            if (remoteServers.isEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "__empty_servers") {
                                    EmptyServersView(
                                        onAddServer = { viewModel.showAddServerDialog() }
                                    )
                                }
                            }

                            items(remoteServers, key = { it.id }) { server ->
                                ServerCard(
                                    server = server,
                                    isConnected = server.id in uiState.connectedServerIds,
                                    isConnecting = server.id in uiState.connectingServerIds,
                                    connectionError = uiState.connectionErrors[server.id],
                                    showServerSettings = server.id in uiState.serverSettingsReadyIds,
                                    onConnect = { requestNotificationPermissionAndConnect(server.id) },
                                    onDisconnect = { viewModel.disconnectFromServer(server.id) },
                                    onOpenSessions = {
                                        onNavigateToSessions(
                                            server.url,
                                            server.username,
                                            server.password ?: "",
                                            server.displayName,
                                            server.id
                                        )
                                    },
                                    onServerSettings = {
                                        onNavigateToServerSettings(
                                            server.url,
                                            server.username,
                                            server.password ?: "",
                                            server.displayName,
                                            server.id
                                        )
                                    },
                                    onEdit = { viewModel.showEditServerDialog(server) },
                                    onDelete = { viewModel.deleteServer(server.id) }
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Battery optimization warning banner
                            if (isBatteryOptimized) {
                                item(key = "__battery_banner") {
                                    BatteryOptimizationBanner(
                                        onDisable = {
                                            val intent = Intent(
                                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        }
                                    )
                                }
                            }

                            if (uiState.showLocalRuntime) {
                                item(key = "__local_runtime") {
                                    LocalRuntimeCard(
                                        termuxInstalled = uiState.termuxInstalled,
                                        runtimeStatus = uiState.localRuntimeStatus,
                                        statusMessage = uiState.localRuntimeMessage,
                                        fixCommand = uiState.localRuntimeFixCommand,
                                        needsOverlaySettings = uiState.localRuntimeNeedsOverlaySettings,
                                        localServerConnected = localServer?.id in uiState.connectedServerIds,
                                        localServerConnecting = localServer?.id in uiState.connectingServerIds,
                                        localServerConnectionError = localServer?.id?.let { uiState.connectionErrors[it] },
                                        showLocalServerSettings = localServer?.id in uiState.serverSettingsReadyIds,
                                        onStart = { requestRunCommandPermissionAndStartLocal() },
                                        onStop = { viewModel.stopLocalServer(context) },
                                        onSetup = {
                                            val setupCommand = uiState.setupCommand ?: viewModel.getLocalSetupCommand()
                                            clipboardManager.setText(AnnotatedString(setupCommand))
                                            Toast.makeText(context, R.string.home_local_setup_copied, Toast.LENGTH_SHORT).show()
                                            viewModel.setupLocalServer(context)
                                        },
                                        onCopyFixCommand = { command ->
                                            clipboardManager.setText(AnnotatedString(command))
                                            Toast.makeText(context, R.string.home_local_fix_command_copied, Toast.LENGTH_SHORT).show()
                                        },
                                        onOpenTermuxOverlaySettings = {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:com.termux"),
                                            )
                                            context.startActivity(intent)
                                        },
                                        onOpenLocalSessions = {
                                            localServer?.let { server ->
                                                onNavigateToSessions(
                                                    server.url,
                                                    server.username,
                                                    server.password ?: "",
                                                    server.displayName,
                                                    server.id,
                                                )
                                            }
                                        },
                                        onOpenLocalServerSettings = {
                                            localServer?.let { server ->
                                                onNavigateToServerSettings(
                                                    server.url,
                                                    server.username,
                                                    server.password ?: "",
                                                    server.displayName,
                                                    server.id,
                                                )
                                            }
                                        },
                                        onOpenLocalLaunchOptions = {
                                            showLocalLaunchOptionsDialog = true
                                        },
                                        onInstallTermux = {
                                            val intent = Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://f-droid.org/packages/com.termux/")
                                            )
                                            context.startActivity(intent)
                                        },
                                    )
                                }
                            }

                            if (remoteServers.isEmpty()) {
                                item(key = "__empty_servers") {
                                    val hasLocalCard = uiState.showLocalRuntime
                                    EmptyServersView(
                                        onAddServer = { viewModel.showAddServerDialog() },
                                        modifier = if (hasLocalCard) {
                                            Modifier.fillParentMaxHeight(0.5f)
                                        } else {
                                            Modifier.fillParentMaxHeight(0.8f)
                                        }
                                    )
                                }
                            }

                            items(remoteServers, key = { it.id }) { server ->
                                ServerCard(
                                    server = server,
                                    isConnected = server.id in uiState.connectedServerIds,
                                    isConnecting = server.id in uiState.connectingServerIds,
                                    connectionError = uiState.connectionErrors[server.id],
                                    showServerSettings = server.id in uiState.serverSettingsReadyIds,
                                    onConnect = { requestNotificationPermissionAndConnect(server.id) },
                                    onDisconnect = { viewModel.disconnectFromServer(server.id) },
                                    onOpenSessions = {
                                        onNavigateToSessions(
                                            server.url,
                                            server.username,
                                            server.password ?: "",
                                            server.displayName,
                                            server.id
                                        )
                                    },
                                    onServerSettings = {
                                        onNavigateToServerSettings(
                                            server.url,
                                            server.username,
                                            server.password ?: "",
                                            server.displayName,
                                            server.id
                                        )
                                    },
                                    onEdit = { viewModel.showEditServerDialog(server) },
                                    onDelete = { viewModel.deleteServer(server.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Add/Edit Server Dialog
        if (uiState.showAddServerDialog) {
            ServerDialog(
                server = uiState.editingServer,
                onDismiss = { viewModel.hideServerDialog() },
                onSave = { name, url, username, password, autoConnect ->
                    viewModel.saveServer(name, url, username, password, autoConnect)
                }
            )
        }

        if (showLocalLaunchOptionsDialog) {
            LocalLaunchOptionsDialog(
                enabled = uiState.localProxyEnabled,
                proxyUrl = uiState.localProxyUrl,
                noProxyList = uiState.localProxyNoProxy,
                allowLanAccess = uiState.localServerAllowLan,
                serverUsername = uiState.localServerUsername,
                serverPassword = uiState.localServerPassword,
                runInBackground = uiState.localServerRunInBackground,
                autoStart = uiState.localServerAutoStart,
                startupTimeoutSec = uiState.localServerStartupTimeoutSec,
                onDismiss = { showLocalLaunchOptionsDialog = false },
                onProxyEnabledChange = viewModel::setLocalProxyEnabled,
                onProxyUrlChange = viewModel::setLocalProxyUrl,
                onNoProxyListChange = viewModel::setLocalProxyNoProxy,
                onAllowLanAccessChange = viewModel::setLocalServerAllowLan,
                onServerUsernameChange = viewModel::setLocalServerUsername,
                onServerPasswordChange = viewModel::setLocalServerPassword,
                onRunInBackgroundChange = viewModel::setLocalServerRunInBackground,
                onAutoStartChange = viewModel::setLocalServerAutoStart,
                onStartupTimeoutSecChange = viewModel::setLocalServerStartupTimeoutSec,
            )
        }

    }
}
