package dev.leonardo.ocremoteplus.ui.navigation.routes

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Common server connection parameters shared across most routes.
 * Eliminates the repeated serverUrl/username/password/serverName/serverId
 * boilerplate in every route definition.
 */
data class ServerRouteParams(
    val serverUrl: String,
    val username: String,
    val password: String,
    val serverName: String,
    val serverId: String
) {
    companion object {
        const val PARAM_SERVER_URL = "serverUrl"
        const val PARAM_USERNAME = "username"
        const val PARAM_PASSWORD = "password"
        const val PARAM_SERVER_NAME = "serverName"
        const val PARAM_SERVER_ID = "serverId"

        /** NavArgument definitions — reuse in every route that needs server params */
        val navArguments = listOf(
            navArgument(PARAM_SERVER_URL) { type = NavType.StringType },
            navArgument(PARAM_USERNAME) { type = NavType.StringType },
            navArgument(PARAM_PASSWORD) { type = NavType.StringType },
            navArgument(PARAM_SERVER_NAME) { type = NavType.StringType },
            navArgument(PARAM_SERVER_ID) { type = NavType.StringType },
        )

        /** Build query pattern with placeholders for route pattern strings */
        fun queryPattern(): String =
            "$PARAM_SERVER_URL={$PARAM_SERVER_URL}&$PARAM_USERNAME={$PARAM_USERNAME}&$PARAM_PASSWORD={$PARAM_PASSWORD}&$PARAM_SERVER_NAME={$PARAM_SERVER_NAME}&$PARAM_SERVER_ID={$PARAM_SERVER_ID}"

        /** Build query string with encoded values for route navigation */
        fun queryString(
            serverUrl: String,
            username: String,
            password: String,
            serverName: String,
            serverId: String
        ): String {
            val encodedUrl = URLEncoder.encode(serverUrl, "UTF-8")
            val encodedUsername = URLEncoder.encode(username, "UTF-8")
            val encodedPassword = URLEncoder.encode(password, "UTF-8")
            val encodedName = URLEncoder.encode(serverName, "UTF-8")
            val encodedServerId = URLEncoder.encode(serverId, "UTF-8")
            return "$PARAM_SERVER_URL=$encodedUrl&$PARAM_USERNAME=$encodedUsername&$PARAM_PASSWORD=$encodedPassword&$PARAM_SERVER_NAME=$encodedName&$PARAM_SERVER_ID=$encodedServerId"
        }
    }
}

/** Extension to decode server params from a NavBackStackEntry. */
fun NavBackStackEntry.serverRouteParams(): ServerRouteParams {
    return ServerRouteParams(
        serverUrl = safeDecodeParam(arguments?.getString(ServerRouteParams.PARAM_SERVER_URL).orEmpty()),
        username = safeDecodeParam(arguments?.getString(ServerRouteParams.PARAM_USERNAME).orEmpty()),
        password = safeDecodeParam(arguments?.getString(ServerRouteParams.PARAM_PASSWORD).orEmpty()),
        serverName = safeDecodeParam(arguments?.getString(ServerRouteParams.PARAM_SERVER_NAME).orEmpty()),
        serverId = safeDecodeParam(arguments?.getString(ServerRouteParams.PARAM_SERVER_ID).orEmpty()),
    )
}
