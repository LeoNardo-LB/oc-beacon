package dev.leonardo.ocbeacon.ui.navigation.routes

import androidx.navigation.NavBackStackEntry

/**
 * 服务器提供商页的导航路由定义。
 * 参数：serverId
 */
object ServerProvidersNav {
    const val ROUTE = "server_providers"

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
