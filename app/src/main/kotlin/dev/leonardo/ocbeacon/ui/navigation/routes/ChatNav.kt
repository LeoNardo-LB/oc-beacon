package dev.leonardo.ocbeacon.ui.navigation.routes

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLEncoder

/**
 * 聊天页的导航路由定义。
 * 参数：serverId, sessionId, openTerminal, directory
 */
object ChatNav {
    const val ROUTE = "chat"

    const val PARAM_SESSION_ID = "sessionId"
    const val PARAM_OPEN_TERMINAL = "openTerminal"
    const val PARAM_DIRECTORY = "directory"
    const val PARAM_JUMP_TO_MESSAGE_ID = "jumpToMessageId"

    val navArguments = ServerRouteParams.navArguments + listOf(
        navArgument(PARAM_SESSION_ID) { type = NavType.StringType },
        navArgument(PARAM_OPEN_TERMINAL) { type = NavType.BoolType; defaultValue = false },
        navArgument(PARAM_DIRECTORY) { type = NavType.StringType; defaultValue = "" },
        navArgument(PARAM_JUMP_TO_MESSAGE_ID) { type = NavType.StringType; defaultValue = "" },
    )

    val routePattern: String
        get() = "$ROUTE?${ServerRouteParams.queryPattern()}&$PARAM_SESSION_ID={$PARAM_SESSION_ID}&$PARAM_OPEN_TERMINAL={$PARAM_OPEN_TERMINAL}&$PARAM_DIRECTORY={$PARAM_DIRECTORY}&$PARAM_JUMP_TO_MESSAGE_ID={$PARAM_JUMP_TO_MESSAGE_ID}"

    data class Params(
        val server: ServerRouteParams,
        val sessionId: String,
        val openTerminal: Boolean = false,
        val directory: String = "",
        /** 内容检索命中跳转目标消息 id（chat 打开即定位，2026-09-01 B1 链）。 */
        val jumpToMessageId: String? = null,
    )

    fun createRoute(
        serverId: String,
        sessionId: String,
        openTerminal: Boolean = false,
        directory: String = "",
        jumpToMessageId: String? = null
    ): String {
        val serverQuery = ServerRouteParams.queryString(serverId)
        val encodedSessionId = URLEncoder.encode(sessionId, "UTF-8")
        val encodedDirectory = URLEncoder.encode(directory, "UTF-8")
        val encodedJump = jumpToMessageId?.let { URLEncoder.encode(it, "UTF-8") }
        val route = "$ROUTE?$serverQuery&$PARAM_SESSION_ID=$encodedSessionId&$PARAM_OPEN_TERMINAL=$openTerminal&$PARAM_DIRECTORY=$encodedDirectory" +
            (encodedJump?.let { "&$PARAM_JUMP_TO_MESSAGE_ID=$it" } ?: "")
        return route
    }

    fun fromEntry(entry: NavBackStackEntry): Params {
        val server = entry.serverRouteParams()
        val sessionId = safeDecodeParam(entry.arguments?.getString(PARAM_SESSION_ID).orEmpty())
        val openTerminal = entry.arguments?.getBoolean(PARAM_OPEN_TERMINAL) ?: false
        val directory = safeDecodeParam(entry.arguments?.getString(PARAM_DIRECTORY).orEmpty())
        val jumpToMessageId = safeDecodeParam(entry.arguments?.getString(PARAM_JUMP_TO_MESSAGE_ID).orEmpty())
            .takeIf { it.isNotBlank() }
        return Params(server = server, sessionId = sessionId, openTerminal = openTerminal, directory = directory, jumpToMessageId = jumpToMessageId)
    }
}