package dev.leonardo.ocbeacon.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * #267（spec §3.1，2026-08-30 裁决「简单做」）：单服务器连接三态。
 *
 * 由 `SseConnectionManager.connectedServerIds × connectingServerIds` 派生，按
 * serverId 键控（Q7a：每界面只看自己会话所属服务器，A 掉线不误伤 B）。
 * - [Connected]：SSE 流活跃；
 * - [Connecting]：尝试中（**含重连退避期**——断连即转 connecting，重连循环常驻）；
 * - [Disconnected]：两个集合都不在（从未连接/已被移除管理）。
 *
 * UI 条幅与写操作守卫统一按「非 Connected」判定（Connecting 对用户同样是
 * 「不可用，正在恢复」——spec §3.2 条幅文案含「正在重连」即此意）。
 */
sealed interface ServerLinkState {
    data object Connected : ServerLinkState
    data object Connecting : ServerLinkState
    data object Disconnected : ServerLinkState

    companion object {
        /** 纯派生（无 IO）——矩阵单测钉住语义。 */
        fun derive(
            serverId: String,
            connected: Set<String>,
            connecting: Set<String>,
        ): ServerLinkState = when {
            serverId in connected -> Connected
            serverId in connecting -> Connecting
            else -> Disconnected
        }
    }
}

/** 派生流工具：两集合任一变化即重派生（去抖由调用方 stateIn/collectAsState 承担）。 */
internal fun deriveLinkStateFlow(
    connected: kotlinx.coroutines.flow.StateFlow<Set<String>>,
    connecting: kotlinx.coroutines.flow.StateFlow<Set<String>>,
    serverId: String,
): Flow<ServerLinkState> = combine(connected, connecting) { c, g ->
    ServerLinkState.derive(serverId, c, g)
}.distinctUntilChanged()
