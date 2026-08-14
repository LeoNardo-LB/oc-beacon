package dev.leonardo.ocbeacon.ui.screens.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.components.indicators.PulsingDotsIndicator
import dev.leonardo.ocbeacon.ui.screens.home.components.*

/**
 * 首页 — 服务器列表与管理
 *
 * 每张服务器卡片都有 连接/断开/会话 按钮。
 * 支持同时连接多个服务器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateToSessions: (serverId: String) -> Unit = {},
    onNavigateToServerSettings: (serverId: String) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    viewModel: HomeViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 跟踪电池优化状态，应用恢复时重新检查
    var isBatteryOptimized by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                isBatteryOptimized = !pm.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 需要记录哪个服务器请求了通知权限，
    // 以便在权限对话框之后恢复连接流程。
    // #115（D2-L24）：rememberSaveable——recreate（语言切换/进程重建）后
    // 权限回调仍需继续连接（原 remember 在 recreate 时丢失 → 回调静默中断）。
    var pendingConnectServerId by rememberSaveable { mutableStateOf<String?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // 无论授权与否，都继续连接
        pendingConnectServerId?.let { viewModel.connectToServer(it) }
        pendingConnectServerId = null
    }

    fun requestNotificationPermissionAndConnect(serverId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pendingConnectServerId = serverId
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.connectToServer(serverId)
        }
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
                    val useGrid = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

                    if (useGrid) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(280.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 电池优化警告横幅
                            if (isBatteryOptimized) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "__battery_banner") {
                                    BatteryOptimizationBanner(
                                        onDisable = {
                                            // Play 合规：不请求 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限，
                                            // 引导用户到系统电池优化设置页手动豁免；
                                            // 部分设备无该入口（ActivityNotFoundException）时回退到应用详情页
                                            try {
                                                context.startActivity(
                                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                                )
                                            } catch (e: Exception) {
                                                context.startActivity(
                                                    Intent(
                                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                        Uri.parse("package:${context.packageName}")
                                                    )
                                                )
                                            }
                                        }
                                    )
                                }
                            }

                            if (uiState.servers.isEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "__empty_servers") {
                                    EmptyServersView(
                                        onAddServer = { viewModel.showAddServerDialog() }
                                    )
                                }
                            }

                            items(uiState.servers, key = { it.id }) { server ->
                                ServerCard(
                                    server = server,
                                    isConnected = server.id in uiState.connectedServerIds,
                                    isConnecting = server.id in uiState.connectingServerIds,
                                    connectionError = uiState.connectionErrors[server.id],
                                    showServerSettings = server.id in uiState.serverSettingsReadyIds,
                                    onConnect = { requestNotificationPermissionAndConnect(server.id) },
                                    onDisconnect = { viewModel.disconnectFromServer(server.id) },
                                    onOpenSessions = {
                                        onNavigateToSessions(server.id)
                                    },
                                    onServerSettings = {
                                        onNavigateToServerSettings(server.id)
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
                            // 电池优化警告横幅
                            if (isBatteryOptimized) {
                                item(key = "__battery_banner") {
                                    BatteryOptimizationBanner(
                                        onDisable = {
                                            // Play 合规：不请求 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限，
                                            // 引导用户到系统电池优化设置页手动豁免；
                                            // 部分设备无该入口（ActivityNotFoundException）时回退到应用详情页
                                            try {
                                                context.startActivity(
                                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                                )
                                            } catch (e: Exception) {
                                                context.startActivity(
                                                    Intent(
                                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                        Uri.parse("package:${context.packageName}")
                                                    )
                                                )
                                            }
                                        }
                                    )
                                }
                            }

                            if (uiState.servers.isEmpty()) {
                                item(key = "__empty_servers") {
                                    EmptyServersView(
                                        onAddServer = { viewModel.showAddServerDialog() },
                                        modifier = Modifier.fillParentMaxHeight(0.8f)
                                    )
                                }
                            }

                            items(uiState.servers, key = { it.id }) { server ->
                                ServerCard(
                                    server = server,
                                    isConnected = server.id in uiState.connectedServerIds,
                                    isConnecting = server.id in uiState.connectingServerIds,
                                    connectionError = uiState.connectionErrors[server.id],
                                    showServerSettings = server.id in uiState.serverSettingsReadyIds,
                                    onConnect = { requestNotificationPermissionAndConnect(server.id) },
                                    onDisconnect = { viewModel.disconnectFromServer(server.id) },
                                    onOpenSessions = {
                                        onNavigateToSessions(server.id)
                                    },
                                    onServerSettings = {
                                        onNavigateToServerSettings(server.id)
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

        // 添加/编辑服务器对话框
        if (uiState.showAddServerDialog) {
            ServerDialog(
                server = uiState.editingServer,
                onDismiss = { viewModel.hideServerDialog() },
                onSave = { name, url, username, password, autoConnect ->
                    viewModel.saveServer(name, url, username, password, autoConnect)
                }
            )
        }

    }
}
