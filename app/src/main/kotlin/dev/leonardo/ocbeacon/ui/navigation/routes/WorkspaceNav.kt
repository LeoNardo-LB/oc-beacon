package dev.leonardo.ocbeacon.ui.navigation.routes

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLEncoder

/**
 * Workspace 页的导航路由定义。
 * 参数：serverId, sessionId, directory
 */
object WorkspaceNav {
    const val ROUTE = "workspace"
    const val PARAM_SESSION_ID = "sessionId"
    const val PARAM_DIRECTORY = "directory"

    val navArguments = ServerRouteParams.navArguments + listOf(
        navArgument(PARAM_SESSION_ID) { type = NavType.StringType },
        navArgument(PARAM_DIRECTORY) { type = NavType.StringType; defaultValue = "" },
    )

    val routePattern: String
        get() = "$ROUTE?${ServerRouteParams.queryPattern()}&$PARAM_SESSION_ID={$PARAM_SESSION_ID}&$PARAM_DIRECTORY={$PARAM_DIRECTORY}"

    data class Params(
        val server: ServerRouteParams,
        val sessionId: String,
        val directory: String = ""
    )

    fun createRoute(
        serverId: String,
        sessionId: String,
        directory: String = ""
    ): String {
        val serverQuery = ServerRouteParams.queryString(serverId)
        val encodedSessionId = URLEncoder.encode(sessionId, "UTF-8")
        val encodedDirectory = URLEncoder.encode(directory, "UTF-8")
        return "$ROUTE?$serverQuery&$PARAM_SESSION_ID=$encodedSessionId&$PARAM_DIRECTORY=$encodedDirectory"
    }

    fun fromEntry(entry: NavBackStackEntry): Params {
        val server = entry.serverRouteParams()
        val sessionId = safeDecodeParam(entry.arguments?.getString(PARAM_SESSION_ID).orEmpty())
        val directory = safeDecodeParam(entry.arguments?.getString(PARAM_DIRECTORY).orEmpty())
        return Params(server = server, sessionId = sessionId, directory = directory)
    }
}
