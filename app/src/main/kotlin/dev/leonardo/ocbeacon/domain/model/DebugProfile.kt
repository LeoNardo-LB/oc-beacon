package dev.leonardo.ocbeacon.domain.model

/**
 * 调试通道参数（#132）—— 由 adb 外部参数构造的服务器连接参数，一键直达会话列表。
 *
 * 仅 debug 构建可用（[dev.leonardo.ocbeacon.BuildConfig.DEBUG] 守卫）；
 * 由 MainActivity 从 intent extra 解析（debug_url / debug_username /
 * debug_password / debug_name），密码不落源码。
 */
data class DebugProfile(
    val id: String,
    val label: String,
    val url: String,
    val username: String = "opencode",
    val password: String,
)
