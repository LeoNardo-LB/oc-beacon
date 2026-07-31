package dev.leonardo.octether.ui.screens.chat

import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.leonardo.octether.ui.navigation.routes.ChatNav

/**
 * Encapsulates the Chat screen's navigation route registration.
 *
 * Route pattern, arguments, and parameter extraction are provided by
 * [ChatNav] in the navigation routes package.
 *
 * Usage in NavGraph:
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
