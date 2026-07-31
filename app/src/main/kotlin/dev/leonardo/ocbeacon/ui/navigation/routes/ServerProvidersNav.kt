package dev.leonardo.ocbeacon.ui.navigation.routes

import androidx.navigation.NavBackStackEntry

/**
 * Navigation route definition for the Server Providers screen.
 * Parameters: serverUrl, username, password, serverName, serverId
 */
object ServerProvidersNav {
    const val ROUTE = "server_providers"

    val navArguments = ServerRouteParams.navArguments

    val routePattern: String
        get() = "$ROUTE?${ServerRouteParams.queryPattern()}"

    data class Params(val server: ServerRouteParams)

    fun createRoute(
        serverUrl: String,
        username: String,
        password: String,
        serverName: String,
        serverId: String
    ): String {
        return "$ROUTE?${ServerRouteParams.queryString(serverUrl, username, password, serverName, serverId)}"
    }

    fun fromEntry(entry: NavBackStackEntry): Params {
        return Params(server = entry.serverRouteParams())
    }
}
