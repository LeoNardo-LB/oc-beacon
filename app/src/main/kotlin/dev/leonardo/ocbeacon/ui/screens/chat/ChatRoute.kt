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
 * 注意：当前 NavGraph.kt 直接内联注册了 chat composable，此扩展函数为
 * 未使用的备用入口（死代码）。保留以备将来重构。签名已同步为单 serverId。
 *
 * 在 NavGraph 中的用法：
 * ```
 * NavGraphBuilder.chatScreen(
 *     onNavigateBack = { ... },
 *     onNavigateToSession = { ... },
 *     onNavigateToChildSession = { ... },
 *     onOpenWorkspace = { ... },
 *     getPendingShare = { sessionId -> ... },
 *     consumeShare = { ... }
 * )
 * ```
 */
@Suppress("unused")
fun NavGraphBuilder.chatScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSession: (serverId: String, sessionId: String) -> Unit,
    onNavigateToChildSession: (serverId: String, sessionId: String) -> Unit,
    /** 2026-08-16（管理入口）：跳服务器模型管理页（模型开关/搜索） */
    onNavigateToModelFilter: () -> Unit,
    onOpenWorkspace: (serverId: String, sessionId: String, directory: String) -> Unit,
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
                onNavigateToSession(args.server.serverId, newSessionId)
            },
            onNavigateToChildSession = { childSessionId ->
                onNavigateToChildSession(args.server.serverId, childSessionId)
            },
            onNavigateToModelFilter = { onNavigateToModelFilter() },
            onOpenWorkspace = {
                onOpenWorkspace(args.server.serverId, args.sessionId, args.directory)
            },
            initialSharedImages = sharedImages,
            onSharedImagesConsumed = consumeShare,
            startInTerminalMode = args.openTerminal,
        )
    }
}
