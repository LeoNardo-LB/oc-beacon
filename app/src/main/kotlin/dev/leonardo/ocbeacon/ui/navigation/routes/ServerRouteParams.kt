package dev.leonardo.ocbeacon.ui.navigation.routes

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLEncoder

/**
 * 多数路由共享的通用服务器参数。
 *
 * 出于安全考虑，导航参数只传 [serverId]——密码、用户名、服务器 URL
 * 不再经 Navigation 传递。各 ViewModel/Screen 通过 [serverId]
 * 从数据源（ServerRepository/ServerConfigRepository）异步解析所需配置。
 */
data class ServerRouteParams(
    val serverId: String
) {
    companion object {
        const val PARAM_SERVER_ID = "serverId"

        /** NavArgument 定义 — 在每个需要服务器参数的路由中复用 */
        val navArguments = listOf(
            navArgument(PARAM_SERVER_ID) { type = NavType.StringType },
        )

        /** 构建带占位符的查询模式，用于路由模式字符串 */
        fun queryPattern(): String = "$PARAM_SERVER_ID={$PARAM_SERVER_ID}"

        /** 用编码后的值构建查询字符串，用于路由导航 */
        fun queryString(serverId: String): String {
            val encodedServerId = URLEncoder.encode(serverId, "UTF-8")
            return "$PARAM_SERVER_ID=$encodedServerId"
        }
    }
}

/** 扩展函数：从 NavBackStackEntry 解码服务器参数。 */
fun NavBackStackEntry.serverRouteParams(): ServerRouteParams {
    return ServerRouteParams(
        serverId = safeDecodeParam(arguments?.getString(ServerRouteParams.PARAM_SERVER_ID).orEmpty()),
    )
}
