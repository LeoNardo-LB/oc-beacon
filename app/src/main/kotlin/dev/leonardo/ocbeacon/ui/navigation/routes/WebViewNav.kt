package dev.leonardo.ocbeacon.ui.navigation.routes

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLEncoder

/**
 * WebView 页（旧版）的导航路由定义。
 * 参数：serverId, initialPath
 *
 * WebViewScreen 内部通过 [serverId] 从 ServerConfigRepository 异步加载
 * 服务器配置（含明文用户名/密码）以构造 Basic Auth 头——明文凭据
 * 不再经导航参数传递。
 */
object WebViewNav {
    const val ROUTE = "webview"

    const val PARAM_INITIAL_PATH = "initialPath"

    val navArguments = ServerRouteParams.navArguments + listOf(
        navArgument(PARAM_INITIAL_PATH) { type = NavType.StringType; defaultValue = "" },
    )

    val routePattern: String
        get() = "$ROUTE?${ServerRouteParams.queryPattern()}&$PARAM_INITIAL_PATH={$PARAM_INITIAL_PATH}"

    data class Params(
        val server: ServerRouteParams,
        val initialPath: String = ""
    )

    fun createRoute(
        serverId: String,
        initialPath: String = ""
    ): String {
        val serverQuery = ServerRouteParams.queryString(serverId)
        val encodedPath = URLEncoder.encode(initialPath, "UTF-8")
        return "$ROUTE?$serverQuery&$PARAM_INITIAL_PATH=$encodedPath"
    }

    fun fromEntry(entry: NavBackStackEntry): Params {
        val server = entry.serverRouteParams()
        val initialPath = safeDecodeParam(entry.arguments?.getString(PARAM_INITIAL_PATH).orEmpty())
        return Params(server = server, initialPath = initialPath)
    }
}
