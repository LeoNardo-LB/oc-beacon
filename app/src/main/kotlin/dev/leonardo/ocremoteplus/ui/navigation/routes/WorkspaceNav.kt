package dev.leonardo.ocremoteplus.ui.navigation.routes

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Navigation route definition for the Workspace screen.
 * Parameters: server params + sessionId, directory
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
        serverUrl: String,
        username: String,
        password: String,
        serverName: String,
        serverId: String,
        sessionId: String,
        directory: String = ""
    ): String {
        val serverQuery = ServerRouteParams.queryString(serverUrl, username, password, serverName, serverId)
        val encodedSessionId = URLEncoder.encode(sessionId, "UTF-8")
        val encodedDirectory = URLEncoder.encode(directory, "UTF-8")
        return "$ROUTE?$serverQuery&$PARAM_SESSION_ID=$encodedSessionId&$PARAM_DIRECTORY=$encodedDirectory"
    }

    fun fromEntry(entry: NavBackStackEntry): Params {
        val server = entry.serverRouteParams()
        val sessionId = entry.arguments?.getString(PARAM_SESSION_ID).orEmpty()
        val directory = entry.arguments?.getString(PARAM_DIRECTORY).orEmpty()
        return Params(server = server, sessionId = sessionId, directory = directory)
    }
}
