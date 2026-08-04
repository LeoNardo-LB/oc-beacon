package dev.leonardo.ocbeacon.ui.navigation.routes

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 聊天页的导航路由定义。
 * 参数：serverUrl, username, password, serverName, serverId, sessionId, openTerminal
 */
object ChatNav {
    const val ROUTE = "chat"

    const val PARAM_SESSION_ID = "sessionId"
    const val PARAM_OPEN_TERMINAL = "openTerminal"
    const val PARAM_DIRECTORY = "directory"

    val navArguments = ServerRouteParams.navArguments + listOf(
        navArgument(PARAM_SESSION_ID) { type = NavType.StringType },
        navArgument(PARAM_OPEN_TERMINAL) { type = NavType.BoolType; defaultValue = false },
        navArgument(PARAM_DIRECTORY) { type = NavType.StringType; defaultValue = "" },
    )

    val routePattern: String
        get() = "$ROUTE?${ServerRouteParams.queryPattern()}&$PARAM_SESSION_ID={$PARAM_SESSION_ID}&$PARAM_OPEN_TERMINAL={$PARAM_OPEN_TERMINAL}&$PARAM_DIRECTORY={$PARAM_DIRECTORY}"

    data class Params(
        val server: ServerRouteParams,
        val sessionId: String,
        val openTerminal: Boolean = false,
        val directory: String = ""
    )

    fun createRoute(
        serverUrl: String,
        username: String,
        password: String,
        serverName: String,
        serverId: String,
        sessionId: String,
        openTerminal: Boolean = false,
        directory: String = ""
    ): String {
        val serverQuery = ServerRouteParams.queryString(serverUrl, username, password, serverName, serverId)
        val encodedSessionId = URLEncoder.encode(sessionId, "UTF-8")
        val encodedDirectory = URLEncoder.encode(directory, "UTF-8")
        val route = "$ROUTE?$serverQuery&$PARAM_SESSION_ID=$encodedSessionId&$PARAM_OPEN_TERMINAL=$openTerminal&$PARAM_DIRECTORY=$encodedDirectory"
        return route
    }

    fun fromEntry(entry: NavBackStackEntry): Params {
        val server = entry.serverRouteParams()
        val sessionId = safeDecodeParam(entry.arguments?.getString(PARAM_SESSION_ID).orEmpty())
        val openTerminal = entry.arguments?.getBoolean(PARAM_OPEN_TERMINAL) ?: false
        val directory = safeDecodeParam(entry.arguments?.getString(PARAM_DIRECTORY).orEmpty())
        return Params(server = server, sessionId = sessionId, openTerminal = openTerminal, directory = directory)
    }
}
