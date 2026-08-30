package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * 服务器类型维度（backlog #276 步骤①；设计文档 §2.1 结构级先行）。
 *
 * 与 [ApiVersion] 正交：ApiVersion 只在 OpenCode 家族内部区分 V1/V2 探测结果；
 * DSH 条目 [ServerType.Dsh] 下 ApiVersion 不参与路由（保持 V1 缺省，探测跳过），
 * 三分优先级：serverType==Dsh → DshApiClient，否则按 apiVersion 二分。
 *
 * 传输差异速查（§1.6）：非标准信封 RPC（POST /api/{method}）+ 双 WS 纯下行
 * 事件流 + /api/respond 回程；无 Basic auth（Host 栅栏按构造通过 adb reverse）。
 */
@Serializable
enum class ServerType {
    /** OpenCode Server（V1/V2 由 [ApiVersion] 探测区分）。 */
    OpenCode,

    /** DeepSeek Harness（DSH）原生部署（dsh web，默认 3080）。 */
    Dsh,
}
