package dev.leonardo.ocbeacon.domain.model

/**
 * 调试通道参数（#132）—— 由 adb 外部参数构造的服务器连接参数，一键直达会话列表。
 *
 * 仅 debug 构建可用（[dev.leonardo.ocbeacon.BuildConfig.DEBUG] 守卫）；
 * 由 MainActivity 从 intent extra 解析（debug_url / debug_username /
 * debug_password / debug_name / debug_server_type），密码不落源码。
 */
data class DebugProfile(
    val id: String,
    val label: String,
    val url: String,
    val username: String = "opencode",
    val password: String,
    /** #319：显式指定的服务器类型（dsh→[ServerType.Dsh]）；null=未指定——
     * 新建按 OpenCode、复用保留既有配置；非 null 时新建与复用均覆写
     * （E2E 靶机 URL 与既有条目同 backend 而类型错配的修正通道）。 */
    val serverType: ServerType? = null,
)
