package dev.leonardo.ocbeacon.domain.model

/**
 * 调试通道套餐（#132）—— 预置的服务器连接参数，一键直达会话列表。
 *
 * 仅 debug 构建可用（[dev.leonardo.ocbeacon.debug.DebugChannel] 以
 * [dev.leonardo.ocbeacon.BuildConfig.DEBUG] 守卫）；密码经 BuildConfig
 * 注入（环境变量 OCB_DEBUG_PWD），不进 VCS。
 */
data class DebugProfile(
    val id: String,
    val label: String,
    val url: String,
    val username: String = "opencode",
    val password: String,
)
