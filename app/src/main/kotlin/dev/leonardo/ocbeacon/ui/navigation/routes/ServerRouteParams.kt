package dev.leonardo.ocbeacon.ui.navigation.routes

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 多数路由共享的通用服务器连接参数。
 * 消除每个路由定义中重复的 serverUrl/username/password/serverName/serverId
 * 样板代码。
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

        /** NavArgument 定义 — 在每个需要服务器参数的路由中复用 */
        val navArguments = listOf(
            navArgument(PARAM_SERVER_URL) { type = NavType.StringType },
            navArgument(PARAM_USERNAME) { type = NavType.StringType },
            navArgument(PARAM_PASSWORD) { type = NavType.StringType },
            navArgument(PARAM_SERVER_NAME) { type = NavType.StringType },
            navArgument(PARAM_SERVER_ID) { type = NavType.StringType },
        )

        /** 构建带占位符的查询模式，用于路由模式字符串 */
        fun queryPattern(): String =
            "$PARAM_SERVER_URL={$PARAM_SERVER_URL}&$PARAM_USERNAME={$PARAM_USERNAME}&$PARAM_PASSWORD={$PARAM_PASSWORD}&$PARAM_SERVER_NAME={$PARAM_SERVER_NAME}&$PARAM_SERVER_ID={$PARAM_SERVER_ID}"

        /** 用编码后的值构建查询字符串，用于路由导航 */
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

/** 扩展函数：从 NavBackStackEntry 解码服务器参数。 */
fun NavBackStackEntry.serverRouteParams(): ServerRouteParams {
    return ServerRouteParams(
        serverUrl = safeDecodeParam(arguments?.getString(ServerRouteParams.PARAM_SERVER_URL).orEmpty()),
        username = safeDecodeParam(arguments?.getString(ServerRouteParams.PARAM_USERNAME).orEmpty()),
        password = safeDecodeParam(arguments?.getString(ServerRouteParams.PARAM_PASSWORD).orEmpty()),
        serverName = safeDecodeParam(arguments?.getString(ServerRouteParams.PARAM_SERVER_NAME).orEmpty()),
        serverId = safeDecodeParam(arguments?.getString(ServerRouteParams.PARAM_SERVER_ID).orEmpty()),
    )
}
