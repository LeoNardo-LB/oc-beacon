package dev.leonardo.octether.ui.navigation.routes

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Navigation route definition for the WebView screen (legacy).
 * Parameters: serverUrl, username, password, serverName, initialPath
 */
object WebViewNav {
    const val ROUTE = "webview"

    const val PARAM_INITIAL_PATH = "initialPath"

    val navArguments = listOf(
        navArgument(ServerRouteParams.PARAM_SERVER_URL) { type = NavType.StringType },
        navArgument(ServerRouteParams.PARAM_USERNAME) { type = NavType.StringType },
        navArgument(ServerRouteParams.PARAM_PASSWORD) { type = NavType.StringType },
        navArgument(ServerRouteParams.PARAM_SERVER_NAME) { type = NavType.StringType },
        navArgument(PARAM_INITIAL_PATH) { type = NavType.StringType; defaultValue = "" },
    )

    val routePattern: String
        get() = "$ROUTE?${ServerRouteParams.PARAM_SERVER_URL}={${ServerRouteParams.PARAM_SERVER_URL}}&${ServerRouteParams.PARAM_USERNAME}={${ServerRouteParams.PARAM_USERNAME}}&${ServerRouteParams.PARAM_PASSWORD}={${ServerRouteParams.PARAM_PASSWORD}}&${ServerRouteParams.PARAM_SERVER_NAME}={${ServerRouteParams.PARAM_SERVER_NAME}}&$PARAM_INITIAL_PATH={$PARAM_INITIAL_PATH}"

    data class Params(
        val serverUrl: String,
        val username: String,
        val password: String,
        val serverName: String,
        val initialPath: String = ""
    )

    fun createRoute(
        serverUrl: String,
        username: String,
        password: String,
        serverName: String,
        initialPath: String = ""
    ): String {
        val encodedUrl = URLEncoder.encode(serverUrl, "UTF-8")
        val encodedUsername = URLEncoder.encode(username, "UTF-8")
        val encodedPassword = URLEncoder.encode(password, "UTF-8")
        val encodedName = URLEncoder.encode(serverName, "UTF-8")
        val encodedPath = URLEncoder.encode(initialPath, "UTF-8")
        return "$ROUTE?${ServerRouteParams.PARAM_SERVER_URL}=$encodedUrl&${ServerRouteParams.PARAM_USERNAME}=$encodedUsername&${ServerRouteParams.PARAM_PASSWORD}=$encodedPassword&${ServerRouteParams.PARAM_SERVER_NAME}=$encodedName&$PARAM_INITIAL_PATH=$encodedPath"
    }

    fun fromEntry(entry: NavBackStackEntry): Params {
        return Params(
            serverUrl = safeDecodeParam(entry.arguments?.getString(ServerRouteParams.PARAM_SERVER_URL).orEmpty()),
            username = safeDecodeParam(entry.arguments?.getString(ServerRouteParams.PARAM_USERNAME).orEmpty()),
            password = safeDecodeParam(entry.arguments?.getString(ServerRouteParams.PARAM_PASSWORD).orEmpty()),
            serverName = safeDecodeParam(entry.arguments?.getString(ServerRouteParams.PARAM_SERVER_NAME).orEmpty()),
            initialPath = safeDecodeParam(entry.arguments?.getString(PARAM_INITIAL_PATH).orEmpty()),
        )
    }
}
