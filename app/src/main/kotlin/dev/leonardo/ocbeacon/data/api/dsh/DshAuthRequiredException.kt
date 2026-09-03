package dev.leonardo.ocbeacon.data.api.dsh

/**
 * DSH 0.1.2+ 鉴权缺失/失效（backlog #317；journal 2026-09-04-dsh-012-adaptation §2.2）。
 *
 * 触发面：RPC 401 / WS 升级 401 / 探测双形态同 401。cookie 缺失、过期或 authority
 * 不匹配均归此类——恢复路径唯一：用户输入 launch token →
 * [DshConnectionRegistry.exchangeToken] 交换新 cookie。
 *
 * 与 [DshApiError] 的关系：**不是其子类**（data class 不可继承）——这是新的失败
 * 模态，连接管理层据此进入 TokenNeeded 状态而非普通断连退避；既有 DshApiError
 * 消费方不受影响（本异常不落入其分类表）。
 *
 * @param authority baseUrl（cookie 绑定的 host:port 语义键）。
 */
class DshAuthRequiredException(
    val authority: String,
    override val message: String = "DSH authentication required for " + authority,
) : Exception(message)
