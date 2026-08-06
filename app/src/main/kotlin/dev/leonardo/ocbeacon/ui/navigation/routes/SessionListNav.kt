package dev.leonardo.ocbeacon.ui.navigation.routes

import androidx.navigation.NavBackStackEntry

/**
 * 会话列表页的导航路由定义。
 * 参数：serverId
 */
object SessionListNav {
    const val ROUTE = "sessions"

    val navArguments = ServerRouteParams.navArguments

    val routePattern: String
        get() = "$ROUTE?${ServerRouteParams.queryPattern()}"

    data class Params(val server: ServerRouteParams)

    fun createRoute(serverId: String): String {
        return "$ROUTE?${ServerRouteParams.queryString(serverId)}"
    }

    fun fromEntry(entry: NavBackStackEntry): Params {
        return Params(server = entry.serverRouteParams())
    }
}
