package dev.leonardo.ocbeacon.ui.navigation.routes

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Navigation route definition for the Session List screen.
 * Parameters: serverUrl, username, password, serverName, serverId
 */
object SessionListNav {
    const val ROUTE = "sessions"

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
