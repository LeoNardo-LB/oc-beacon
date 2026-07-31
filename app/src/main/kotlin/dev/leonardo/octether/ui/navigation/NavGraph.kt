package dev.leonardo.octether.ui.navigation

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import dev.leonardo.octether.BuildConfig
import dev.leonardo.octether.ui.theme.AppMotion
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.leonardo.octether.SessionDeepLink
import dev.leonardo.octether.domain.repository.ServerRepository
import dev.leonardo.octether.domain.repository.FileRepository
import dev.leonardo.octether.domain.repository.SessionRepository
import dev.leonardo.octether.domain.repository.SettingsRepository
import dev.leonardo.octether.domain.model.Session
import dev.leonardo.octether.ui.navigation.routes.*
import kotlinx.coroutines.launch
import dev.leonardo.octether.ui.screens.about.AboutScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.leonardo.octether.ui.screens.chat.ChatScreen
import dev.leonardo.octether.ui.screens.chat.ChatViewModel
import dev.leonardo.octether.ui.screens.home.HomeRoute
import dev.leonardo.octether.ui.screens.sessions.CrossServerSessionsScreen
import dev.leonardo.octether.ui.screens.sessions.SessionListRoute
import dev.leonardo.octether.ui.screens.server.ServerModelFilterRoute
import dev.leonardo.octether.ui.screens.server.ServerProvidersRoute
import dev.leonardo.octether.ui.screens.server.ServerSettingsRoute
import dev.leonardo.octether.ui.screens.settings.SettingsRoute
import dev.leonardo.octether.ui.screens.webview.WebViewScreen
import dev.leonardo.octether.ui.screens.workspace.WorkspaceRoute
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.firstOrNull
import java.net.URLDecoder
import androidx.compose.material3.windowsizeclass.WindowSizeClass

private const val TAG = "NavGraph"

/**
 * Main navigation graph for the app.
 * Route patterns, arguments, and parameter extraction are delegated to
 * Nav objects in [dev.leonardo.octether.ui.navigation.routes].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun NavGraph(
    windowSizeClass: WindowSizeClass,
    deepLinkFlow: MutableSharedFlow<SessionDeepLink>,
    sharedImagesFlow: SharedFlow<List<Uri>>,
    settingsRepository: SettingsRepository,
    serverRepository: ServerRepository,
    sessionRepository: SessionRepository,
    fileRepository: FileRepository
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    // Use native UI by default (WebView is legacy)
    val useNativeUi = true

    // Flow to tell the *existing* WebView to navigate to a new URL
    // (used when deep-link arrives while WebView is already on screen)
    val webViewNavigateFlow = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }

    // ============ Share Target Picker state ============
    var showSharePicker by remember { mutableStateOf(false) }
    var pendingShareUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    // Target session that should receive the shared images (null = not yet chosen)
    var pendingShareSessionId by remember { mutableStateOf<String?>(null) }
    // Data for the picker dialog
    var sharePickerServers by remember { mutableStateOf<List<dev.leonardo.octether.domain.model.ServerConfig>>(emptyList()) }
    var sharePickerSessions by remember { mutableStateOf<List<dev.leonardo.octether.domain.model.Session>>(emptyList()) }
    var sharePickerServerSessions by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }

    // Listen for shared images
    LaunchedEffect(Unit) {
        sharedImagesFlow.collect { uris ->
            if (uris.isEmpty()) return@collect
            Log.i(TAG, "Shared images received: ${uris.size} URIs")

            // Store pending URIs (will be consumed by the target ChatScreen)
            pendingShareUris = uris
            pendingShareSessionId = null

            // If we're already in a ChatScreen, target the current session directly
            val currentRoute = navController.currentDestination?.route
            if (currentRoute?.startsWith("chat") == true) {
                val currentSessionId = navController.currentBackStackEntry
                    ?.arguments?.getString("sessionId")
                if (currentSessionId != null) {
                    Log.i(TAG, "Already in ChatScreen for session $currentSessionId, targeting it directly")
                    pendingShareSessionId = currentSessionId
                    return@collect
                }
            }

            // Otherwise, show the session picker.
            // Use local vars to avoid mid-coroutine state writes triggering
            // recomposition before all data is ready.
            val servers = serverRepository.getServersFlow().firstOrNull() ?: emptyList()
            val allSessions = mutableListOf<Session>()
            val sessionMap = mutableMapOf<String, Set<String>>()
            for (sv in servers) {
                val serverSessions = sessionRepository.getSessionsFlow(sv.id).firstOrNull() ?: emptyList()
                allSessions.addAll(serverSessions)
                sessionMap[sv.id] = serverSessions.map { it.id }.toSet()
            }
            // Batch state updates at the end — atomic from recomposition's perspective
            sharePickerServers = servers
            sharePickerSessions = allSessions
            sharePickerServerSessions = sessionMap
            showSharePicker = true
        }
    }

    // Share Target Picker Dialog
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
                    serverUrl = server.url,
                    username = server.username,
                    password = server.password ?: "",
                    serverName = server.displayName,
                    serverId = server.id,
                    sessionId = session.id
                )
                Log.i(TAG, "Share → navigating to session ${session.id} on ${server.displayName}")
                navController.navigate(route) { launchSingleTop = true }
            },
            onNewSession = { server ->
                showSharePicker = false
                // Navigate to session list — user can create a new session there.
                // Images remain in the flow and will be consumed when ChatScreen opens.
                val route = SessionListNav.createRoute(
                    serverUrl = server.url,
                    username = server.username,
                    password = server.password ?: "",
                    serverName = server.displayName,
                    serverId = server.id
                )
                Log.i(TAG, "Share → navigating to session list on ${server.displayName}")
                navController.navigate(route) { launchSingleTop = true }
            },
            onDismiss = {
                showSharePicker = false
                pendingShareUris = emptyList()
            }
        )
    }

    // Listen for deep-link events from notification taps
    LaunchedEffect(Unit) {
        deepLinkFlow.collect { deepLink ->
            // Consume the event so it's not replayed on recomposition
            deepLinkFlow.resetReplayCache()
            val currentRoute = navController.currentDestination?.route
            if (BuildConfig.DEBUG) Log.d(TAG, "Deep-link received: sessionPath=${deepLink.sessionPath}, sessionId=${deepLink.sessionId}, currentRoute=$currentRoute, useNativeUi=$useNativeUi")

            if (useNativeUi) {
                // ---- Native UI path ----
                val sessionId = deepLink.sessionPath
                    .trimEnd('/')
                    .substringAfterLast("/session/", "")
                    .takeIf { it.isNotBlank() }
                    ?: deepLink.sessionId.takeIf { it.isNotBlank() }

                if (sessionId != null) {
                    val route = ChatNav.createRoute(
                        serverUrl = deepLink.serverUrl,
                        username = deepLink.username,
                        password = deepLink.password,
                        serverName = deepLink.serverName,
                        serverId = deepLink.serverId,
                        sessionId = sessionId
                    )
                val currentSessionId = navController.currentBackStackEntry
                    ?.arguments
                    ?.getString("sessionId")

                    Log.i(TAG, "Deep-link → native Chat: targetSession=$sessionId currentSession=$currentSessionId")

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
                } else {
                    Log.i(TAG, "Deep-link has no sessionId, ignoring native path")
                }
            } else {
                // ---- WebView path (legacy) ----
                val isWebViewOnScreen = currentRoute?.startsWith("webview") == true

                if (isWebViewOnScreen && deepLink.sessionPath.isNotBlank()) {
                    val newUrl = deepLink.serverUrl.trimEnd('/') + deepLink.sessionPath
                    Log.i(TAG, "WebView already on screen, navigating in-place to: $newUrl")
                    webViewNavigateFlow.tryEmit(newUrl)
                } else {
                    val route = WebViewNav.createRoute(
                        serverUrl = deepLink.serverUrl,
                        username = deepLink.username,
                        password = deepLink.password,
                        serverName = deepLink.serverName,
                        initialPath = deepLink.sessionPath
                    )
                    Log.i(TAG, "Deep-link → WebView: $route")
                    navController.navigate(route) { launchSingleTop = true }
                }
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
        // ============ Home Screen ============
        composable(HomeNav.route) {
            HomeRoute(
                windowSizeClass = windowSizeClass,
                onNavigateToSessions = { serverUrl, username, password, serverName, serverId ->
                    navController.navigate(
                        SessionListNav.createRoute(serverUrl, username, password, serverName, serverId)
                    )
                },
                onNavigateToServerSettings = { serverUrl, username, password, serverName, serverId ->
                    navController.navigate(
                        ServerSettingsNav.createRoute(serverUrl, username, password, serverName, serverId)
                    )
                },
                onNavigateToSettings = {
                    navController.navigate(SettingsNav.route)
                },
                onNavigateToAbout = {
                    navController.navigate(AboutNav.route)
                }
            )
        }

        // ============ Settings Screen ============
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

        // ============ Diagnostics Screen ============
        composable(DiagnosticsNav.route) {
            dev.leonardo.octether.ui.screens.settings.DiagnosticsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // ============ Cross-Server Favorites Screen ============
        composable(CrossServerFavoritesNav.route) {
            CrossServerSessionsScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenSession = { item ->
                    scope.launch {
                        val server = serverRepository.getServer(item.serverId) ?: return@launch
                        navController.navigate(
                            ChatNav.createRoute(
                                serverUrl = server.url,
                                username = server.username,
                                password = server.password ?: "",
                                serverName = server.displayName,
                                serverId = server.id,
                                sessionId = item.sessionId,
                            )
                        )
                    }
                },
                onConnectServer = { serverId ->
                    scope.launch {
                        serverRepository.getServer(serverId)?.let { serverRepository.connect(it) }
                    }
                },
            )
        }

        // ============ Server Settings Screen ============
        composable(
            route = ServerSettingsNav.routePattern,
            arguments = ServerSettingsNav.navArguments
        ) { entry ->
            val params = ServerSettingsNav.fromEntry(entry)
            ServerSettingsRoute(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToProviders = {
                    navController.navigate(
                        ServerProvidersNav.createRoute(
                            serverUrl = params.server.serverUrl,
                            username = params.server.username,
                            password = params.server.password,
                            serverName = params.server.serverName,
                            serverId = params.server.serverId
                        )
                    )
                },
                onNavigateToModelFilter = {
                    navController.navigate(
                        ServerModelFilterNav.createRoute(
                            serverUrl = params.server.serverUrl,
                            username = params.server.username,
                            password = params.server.password,
                            serverName = params.server.serverName,
                            serverId = params.server.serverId
                        )
                    )
                }
            )
        }

        // ============ Server Providers Screen ============
        composable(
            route = ServerProvidersNav.routePattern,
            arguments = ServerProvidersNav.navArguments
        ) {
            ServerProvidersRoute(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // ============ Server Model Filter Screen ============
        composable(
            route = ServerModelFilterNav.routePattern,
            arguments = ServerModelFilterNav.navArguments
        ) {
            ServerModelFilterRoute(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // ============ About Screen ============
        composable(AboutNav.route) {
            AboutScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // ============ WebView Screen (legacy) ============
        composable(
            route = WebViewNav.routePattern,
            arguments = WebViewNav.navArguments
        ) { entry ->
            val params = WebViewNav.fromEntry(entry)

            WebViewScreen(
                serverUrl = params.serverUrl,
                username = params.username,
                password = params.password,
                serverName = params.serverName,
                initialPath = params.initialPath,
                navigateUrlFlow = webViewNavigateFlow,
                isDarkTheme = isSystemInDarkTheme(),
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // ============ Session List Screen (native) ============
        composable(
            route = SessionListNav.routePattern,
            arguments = SessionListNav.navArguments
        ) { entry ->
            val params = SessionListNav.fromEntry(entry)

            SessionListRoute(
                onNavigateToChat = { sessionId, openTerminal ->
                    navController.navigate(
                        ChatNav.createRoute(
                            serverUrl = params.server.serverUrl,
                            username = params.server.username,
                            password = params.server.password,
                            serverName = params.server.serverName,
                            serverId = params.server.serverId,
                            sessionId = sessionId,
                            openTerminal = openTerminal
                        )
                    )
                },
                onNavigateToNewChat = { directory ->
                    navController.navigate(
                        ChatNav.createRoute(
                            serverUrl = params.server.serverUrl,
                            username = params.server.username,
                            password = params.server.password,
                            serverName = params.server.serverName,
                            serverId = params.server.serverId,
                            sessionId = "",
                            directory = directory
                        )
                    )
                },
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToFavorites = {
                    navController.navigate(CrossServerFavoritesNav.route)
                }
            )
        }

        // ============ Chat Screen (native) ============
        composable(
            route = ChatNav.routePattern,
            arguments = ChatNav.navArguments
        ) { entry ->
            val params = ChatNav.fromEntry(entry)
            val context = LocalContext.current

            // Only pass shared images to the targeted session, then clear them
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
                        serverUrl = params.server.serverUrl,
                        username = params.server.username,
                        password = params.server.password,
                        serverName = params.server.serverName,
                        serverId = params.server.serverId,
                        sessionId = newSessionId,
                        directory = if (newSessionId.isEmpty()) params.directory else ""
                    )
                    navController.navigate(route) {
                        // Pop current chat so back goes to session list, not old session
                        popUpTo("sessions") {
                            inclusive = false
                        }
                    }
                },
                onNavigateToChildSession = { childSessionId ->
                    val route = ChatNav.createRoute(
                        serverUrl = params.server.serverUrl,
                        username = params.server.username,
                        password = params.server.password,
                        serverName = params.server.serverName,
                        serverId = params.server.serverId,
                        sessionId = childSessionId
                    )
                    navController.navigate(route)
                },
                onOpenInWebView = {
                    scope.launch {
                        val session = sessionRepository.getSession(params.server.serverId, params.sessionId).getOrNull()
                        val dir = session?.directory ?: ""
                        val encodedDir = android.util.Base64.encodeToString(
                            dir.toByteArray(Charsets.UTF_8),
                            android.util.Base64.NO_WRAP
                        ).replace('+', '-').replace('/', '_').replace("=", "")
                        val sessionPath = "/$encodedDir/session/${params.sessionId}"
                        val route = WebViewNav.createRoute(
                            serverUrl = params.server.serverUrl,
                            username = params.server.username,
                            password = params.server.password,
                            serverName = params.server.serverName,
                            initialPath = sessionPath
                        )
                        navController.navigate(route) { launchSingleTop = true }
                    }
                },
                onOpenWorkspace = {
                    scope.launch {
                        val session = sessionRepository.getSession(params.server.serverId, params.sessionId).getOrNull()
                        val dir = session?.directory ?: params.directory
                        navController.navigate(
                            WorkspaceNav.createRoute(
                                serverUrl = params.server.serverUrl,
                                username = params.server.username,
                                password = params.server.password,
                                serverName = params.server.serverName,
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
                            serverUrl = params.server.serverUrl,
                            username = params.server.username,
                            password = params.server.password,
                            serverName = params.server.serverName,
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

        // ============ Workspace Screen ============
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
