package dev.leonardo.ocbeacon.ui.screens.chat

import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.leonardo.ocbeacon.ui.navigation.routes.ChatNav

/**
 * 封装 Chat 屏幕的导航路由注册。
 *
 * 路由模式、参数和参数提取由
 * 导航路由包中的 [ChatNav] 提供。
 *
 * 在 NavGraph 中的用法：
 * ```
 * NavGraphBuilder.chatScreen(
 *     onNavigateBack = { ... },
 *     onNavigateToSession = { ... },
 *     onNavigateToChildSession = { ... },
 *     onOpenInWebView = { ... },
 *     onOpenWorkspace = { ... },
 *     getPendingShare = { sessionId -> ... },
 *     consumeShare = { ... }
 * )
 * ```
 */
fun NavGraphBuilder.chatScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSession: (serverUrl: String, username: String, password: String,
                          serverName: String, serverId: String, sessionId: String) -> Unit,
    onNavigateToChildSession: (serverUrl: String, username: String, password: String,
                               serverName: String, serverId: String, sessionId: String) -> Unit,
    onOpenInWebView: (serverUrl: String, username: String, password: String,
                      serverName: String, sessionId: String) -> Unit,
    onOpenWorkspace: (serverUrl: String, username: String, password: String,
                      serverName: String, serverId: String, sessionId: String,
                      directory: String) -> Unit,
    getPendingShare: (sessionId: String) -> List<Uri>,
    consumeShare: () -> Unit,
) {
    composable(
        route = ChatNav.routePattern,
        arguments = ChatNav.navArguments,
    ) { backStackEntry ->
        val args = ChatNav.fromEntry(backStackEntry)

        val sharedImages = getPendingShare(args.sessionId)

        ChatScreen(
            serverId = args.server.serverId,
            sessionId = args.sessionId,
            onNavigateBack = onNavigateBack,
            onNavigateToSession = { newSessionId ->
                onNavigateToSession(
                    args.server.serverUrl, args.server.username, args.server.password,
                    args.server.serverName, args.server.serverId, newSessionId,
                )
            },
            onNavigateToChildSession = { childSessionId ->
                onNavigateToChildSession(
                    args.server.serverUrl, args.server.username, args.server.password,
                    args.server.serverName, args.server.serverId, childSessionId,
                )
            },
            onOpenInWebView = {
                onOpenInWebView(
                    args.server.serverUrl, args.server.username, args.server.password,
                    args.server.serverName, args.sessionId,
                )
            },
            onOpenWorkspace = {
                onOpenWorkspace(
                    args.server.serverUrl, args.server.username, args.server.password,
                    args.server.serverName, args.server.serverId, args.sessionId,
                    args.directory,
                )
            },
            initialSharedImages = sharedImages,
            onSharedImagesConsumed = consumeShare,
            startInTerminalMode = args.openTerminal,
        )
    }
}
