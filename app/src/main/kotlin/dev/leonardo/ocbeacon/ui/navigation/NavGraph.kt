package dev.leonardo.ocbeacon.ui.navigation

import dev.leonardo.ocbeacon.logging.AppLogger

import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.ui.theme.AppMotion
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.leonardo.ocbeacon.SessionDeepLink
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.ui.navigation.routes.*
import kotlinx.coroutines.launch
import dev.leonardo.ocbeacon.ui.screens.about.AboutScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.leonardo.ocbeacon.ui.screens.chat.ChatScreen
import dev.leonardo.ocbeacon.ui.screens.chat.ChatViewModel
import dev.leonardo.ocbeacon.ui.screens.home.HomeRoute
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionListRoute
import dev.leonardo.ocbeacon.ui.screens.server.ServerModelFilterRoute
import dev.leonardo.ocbeacon.ui.screens.server.ServerProvidersRoute
import dev.leonardo.ocbeacon.ui.screens.server.ServerSettingsRoute
import dev.leonardo.ocbeacon.ui.screens.settings.SettingsRoute
import dev.leonardo.ocbeacon.ui.screens.webview.WebViewScreen
import dev.leonardo.ocbeacon.ui.screens.workspace.WorkspaceRoute
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.firstOrNull
import androidx.compose.material3.windowsizeclass.WindowSizeClass

private const val TAG = "NavGraph"

/**
 * 应用主导航图。
 * 路由模式、参数和参数提取委托给
 * [dev.leonardo.ocbeacon.ui.navigation.routes] 中的 Nav 对象。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun NavGraph(
    windowSizeClass: WindowSizeClass,
    deepLinkFlow: MutableSharedFlow<SessionDeepLink>,
    debugChannelFlow: MutableSharedFlow<String>,
    sharedImagesFlow: SharedFlow<List<Uri>>,
    settingsRepository: SettingsRepository,
    serverRepository: ServerRepository,
    sessionRepository: SessionRepository,
    fileRepository: FileRepository
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    // 默认使用原生 UI（WebView 为旧版实现）
    val useNativeUi = true

    // 用于通知已存在的 WebView 导航到新 URL 的 Flow
    //（用于深度链接到达时 WebView 已经在屏幕上的场景）
    val webViewNavigateFlow = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }

    // ============ 分享目标选择器状态 ============
    var showSharePicker by remember { mutableStateOf(false) }
    var pendingShareUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    // 应该接收这些分享图片的目标会话（null = 尚未选择）
    var pendingShareSessionId by remember { mutableStateOf<String?>(null) }
    // 选择器对话框的数据
    var sharePickerServers by remember { mutableStateOf<List<dev.leonardo.ocbeacon.domain.model.ServerConfig>>(emptyList()) }
    var sharePickerSessions by remember { mutableStateOf<List<dev.leonardo.ocbeacon.domain.model.Session>>(emptyList()) }
    var sharePickerServerSessions by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }

    // 监听分享的图片
    LaunchedEffect(Unit) {
        sharedImagesFlow.collect { uris ->
            if (uris.isEmpty()) return@collect
            AppLogger.i(TAG, "Shared images received: ${uris.size} URIs")

            // 暂存待处理的 URI（将由目标 ChatScreen 消费）
            pendingShareUris = uris
            pendingShareSessionId = null

            // 如果已经在 ChatScreen 中，直接定向到当前会话
            val currentRoute = navController.currentDestination?.route
            if (currentRoute?.startsWith("chat") == true) {
                val currentSessionId = navController.currentBackStackEntry
                    ?.arguments?.getString("sessionId")
                if (currentSessionId != null) {
                    AppLogger.i(TAG, "Already in ChatScreen for session $currentSessionId, targeting it directly")
                    pendingShareSessionId = currentSessionId
                    return@collect
                }
            }

            // 否则，显示会话选择器。
            // 使用局部变量，避免协程中段写入状态触发
            // 所有数据就绪前的重组。
            val servers = serverRepository.getServersFlow().firstOrNull() ?: emptyList()
            val allSessions = mutableListOf<Session>()
            val sessionMap = mutableMapOf<String, Set<String>>()
            for (sv in servers) {
                val serverSessions = sessionRepository.getSessionsFlow(sv.id).firstOrNull() ?: emptyList()
                allSessions.addAll(serverSessions)
                sessionMap[sv.id] = serverSessions.map { it.id }.toSet()
            }
            // 在末尾批量更新状态 — 从重组视角看是原子的
            sharePickerServers = servers
            sharePickerSessions = allSessions
            sharePickerServerSessions = sessionMap
            showSharePicker = true
        }
    }

    // 分享目标选择器对话框
    if (showSharePicker && pendingShareUris.isNotEmpty()) {
        ShareTargetPickerDialog(
            servers = sharePickerServers,
            sessions = sharePickerSessions,
            serverSessions = sharePickerServerSessions,
            imageCount = pendingShareUris.size,
            onSelectSession = { server, session ->
                showSharePicker = false
                pendingShareSessionId = session.id
                val route = ChatNav.createRoute(
                    serverId = server.id,
                    sessionId = session.id
                )
                AppLogger.i(TAG, "Share → navigating to session ${session.id} on ${server.displayName}")
                navController.navigate(route) { launchSingleTop = true }
            },
            onNewSession = { server ->
                showSharePicker = false
                // 导航到会话列表 — 用户可在那里创建新会话。
                // 图片仍保留在 flow 中，会在 ChatScreen 打开时被消费。
                val route = SessionListNav.createRoute(server.id)
                AppLogger.i(TAG, "Share → navigating to session list on ${server.displayName}")
                navController.navigate(route) { launchSingleTop = true }
            },
            onDismiss = {
                showSharePicker = false
                pendingShareUris = emptyList()
            }
        )
    }

    // #132 调试通道：外部参数（am start --es debug_profile <id>）激活后直达会话列表
    LaunchedEffect(Unit) {
        debugChannelFlow.collect { serverId ->
            debugChannelFlow.resetReplayCache()
            AppLogger.i(TAG, "Debug channel → SessionList for server $serverId")
            navController.navigate(SessionListNav.createRoute(serverId)) { launchSingleTop = true }
        }
    }

    // 监听来自通知点击的深度链接事件
    LaunchedEffect(Unit) {
        deepLinkFlow.collect { deepLink ->
            // 消费事件，避免重组时重放
            deepLinkFlow.resetReplayCache()
            val currentRoute = navController.currentDestination?.route
            if (BuildConfig.DEBUG) AppLogger.d(TAG, "Deep-link received: sessionPath=${deepLink.sessionPath}, sessionId=${deepLink.sessionId}, currentRoute=$currentRoute, useNativeUi=$useNativeUi")

            if (useNativeUi) {
                // ---- 原生 UI 路径 ----
                val sessionId = deepLink.sessionPath
                    .trimEnd('/')
                    .substringAfterLast("/session/", "")
                    .takeIf { it.isNotBlank() }
                    ?: deepLink.sessionId.takeIf { it.isNotBlank() }

                if (sessionId != null) {
                    val route = ChatNav.createRoute(
                        serverId = deepLink.serverId,
                        sessionId = sessionId
                    )
                val currentSessionId = navController.currentBackStackEntry
                    ?.arguments
                    ?.getString("sessionId")

                    AppLogger.i(TAG, "Deep-link → native Chat: targetSession=$sessionId currentSession=$currentSessionId")

                    if (currentRoute?.startsWith("chat") == true && currentSessionId != sessionId) {
                        navController.navigate(route) {
                            popUpTo(navController.currentDestination?.route ?: return@navigate) {
                                inclusive = true
                            }
                            launchSingleTop = true
                        }
                    } else {
                        navController.navigate(route) { launchSingleTop = true }
                    }
                } else if (deepLink.serverId.isNotBlank()) {
                    // 持久通知点击（无 sessionId）→ 打开该服务器的会话列表
                    AppLogger.i(TAG, "Deep-link → native SessionList for ${deepLink.serverId}")
                    val route = SessionListNav.createRoute(deepLink.serverId)
                    navController.navigate(route) { launchSingleTop = true }
                } else {
                    AppLogger.i(TAG, "Deep-link has no sessionId, ignoring native path")
                }
            } else {
                // ---- WebView 路径（旧版） ----
                val route = WebViewNav.createRoute(
                    serverId = deepLink.serverId,
                    initialPath = deepLink.sessionPath
                )
                AppLogger.i(TAG, "Deep-link → WebView: $route")
                navController.navigate(route) { launchSingleTop = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        enterTransition = { fadeIn(animationSpec = tween(AppMotion.MEDIUM)) },
        exitTransition = { fadeOut(animationSpec = tween(AppMotion.MEDIUM)) },
        popEnterTransition = { fadeIn(animationSpec = tween(AppMotion.MEDIUM)) },
        popExitTransition = { fadeOut(animationSpec = tween(AppMotion.MEDIUM)) }
    ) {
        // ============ 首页 ============
        composable(HomeNav.route) {
            HomeRoute(
                windowSizeClass = windowSizeClass,
                onNavigateToSessions = { serverId ->
                    navController.navigate(SessionListNav.createRoute(serverId))
                },
                onNavigateToServerSettings = { serverId ->
                    navController.navigate(ServerSettingsNav.createRoute(serverId))
                },
                onNavigateToSettings = {
                    navController.navigate(SettingsNav.route)
                },
                onNavigateToAbout = {
                    navController.navigate(AboutNav.route)
                }
            )
        }

        // ============ 设置页 ============
        composable(SettingsNav.route) {
            SettingsRoute(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToDiagnostics = {
                    navController.navigate(DiagnosticsNav.route)
                }
            )
        }

        // ============ 诊断页 ============
        composable(DiagnosticsNav.route) {
            dev.leonardo.ocbeacon.ui.screens.settings.DiagnosticsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // ============ 服务器设置页 ============
        composable(
            route = ServerSettingsNav.routePattern,
            arguments = ServerSettingsNav.navArguments
        ) { entry ->
            val params = ServerSettingsNav.fromEntry(entry)
            ServerSettingsRoute(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToProviders = {
                    navController.navigate(ServerProvidersNav.createRoute(params.server.serverId))
                },
                onNavigateToModelFilter = {
                    navController.navigate(ServerModelFilterNav.createRoute(params.server.serverId))
                }
            )
        }

        // ============ 服务器提供商页 ============
        composable(
            route = ServerProvidersNav.routePattern,
            arguments = ServerProvidersNav.navArguments
        ) {
            ServerProvidersRoute(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // ============ 服务器模型过滤页 ============
        composable(
            route = ServerModelFilterNav.routePattern,
            arguments = ServerModelFilterNav.navArguments
        ) {
            ServerModelFilterRoute(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // ============ 关于页 ============
        composable(AboutNav.route) {
            AboutScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // ============ WebView 页（旧版） ============
        composable(
            route = WebViewNav.routePattern,
            arguments = WebViewNav.navArguments
        ) { entry ->
            val params = WebViewNav.fromEntry(entry)

            WebViewScreen(
                serverId = params.server.serverId,
                initialPath = params.initialPath,
                serverConfigRepository = serverRepository,
                navigateUrlFlow = webViewNavigateFlow,
                isDarkTheme = isSystemInDarkTheme(),
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // ============ 会话列表页（原生） ============
        composable(
            route = SessionListNav.routePattern,
            arguments = SessionListNav.navArguments
        ) { entry ->
            val params = SessionListNav.fromEntry(entry)

            SessionListRoute(
                onNavigateToChat = { sessionId, openTerminal ->
                    navController.navigate(
                        ChatNav.createRoute(
                            serverId = params.server.serverId,
                            sessionId = sessionId,
                            openTerminal = openTerminal
                        )
                    )
                },
                onNavigateToNewChat = { directory ->
                    navController.navigate(
                        ChatNav.createRoute(
                            serverId = params.server.serverId,
                            sessionId = "",
                            directory = directory
                        )
                    )
                },
                onNavigateBack = {
                    navController.popBackStack()
                },
            )
        }

        // ============ 聊天页（原生） ============
        composable(
            route = ChatNav.routePattern,
            arguments = ChatNav.navArguments
        ) { entry ->
            val params = ChatNav.fromEntry(entry)
            val context = LocalContext.current

            // 仅把分享的图片传给目标会话，然后清空
            val imagesForThisSession = if (pendingShareSessionId == params.sessionId && pendingShareUris.isNotEmpty()) {
                pendingShareUris
            } else {
                emptyList()
            }

            val chatViewModel = hiltViewModel<ChatViewModel>()
                ChatScreen(
                    serverId = params.server.serverId,
                    sessionId = params.sessionId,
                    viewModel = chatViewModel,
                    onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToSession = { newSessionId ->
                    val route = ChatNav.createRoute(
                        serverId = params.server.serverId,
                        sessionId = newSessionId,
                        directory = if (newSessionId.isEmpty()) params.directory else ""
                    )
                    navController.navigate(route) {
                        // 弹出当前 chat，使返回键回到会话列表而非旧会话
                        popUpTo("sessions") {
                            inclusive = false
                        }
                    }
                },
                onNavigateToChildSession = { childSessionId ->
                    val route = ChatNav.createRoute(
                        serverId = params.server.serverId,
                        sessionId = childSessionId
                    )
                    // #137（D2-L32）：与其他 9 处 navigate 一致加 launchSingleTop——
                    // 重复打开同一子会话会栈顶叠加多个相同路由
                    navController.navigate(route) { launchSingleTop = true }
                },
                onOpenWorkspace = {
                    scope.launch {
                        val session = sessionRepository.getSession(params.server.serverId, params.sessionId).getOrNull()
                        val dir = session?.directory ?: params.directory
                        navController.navigate(
                            WorkspaceNav.createRoute(
                                serverId = params.server.serverId,
                                sessionId = params.sessionId,
                                directory = dir
                            )
                        ) { launchSingleTop = true }
                    }
                },
                onOpenDirectory = { directoryPath ->
                    navController.navigate(
                        WorkspaceNav.createRoute(
                            serverId = params.server.serverId,
                            sessionId = params.sessionId,
                            directory = directoryPath
                        )
                    ) { launchSingleTop = true }
                },
                checkFileExists = { filePath ->
                    val session = sessionRepository.getSession(params.server.serverId, params.sessionId).getOrNull()
                    val dir = session?.directory ?: params.directory
                    val result = fileRepository.getFileContent(params.server.serverId, dir, filePath)
                    result.isSuccess && result.getOrNull()?.content?.isNotEmpty() == true
                },
                initialSharedImages = imagesForThisSession,
                onSharedImagesConsumed = {
                    pendingShareUris = emptyList()
                    pendingShareSessionId = null
                },
                startInTerminalMode = params.openTerminal
            )
        }

        // ============ Workspace 页 ============
        composable(
            route = WorkspaceNav.routePattern,
            arguments = WorkspaceNav.navArguments
        ) { entry ->
            val p = WorkspaceNav.fromEntry(entry)
            WorkspaceRoute(
                serverId = p.server.serverId,
                sessionId = p.sessionId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
