package dev.leonardo.ocbeacon.domain.model

/**
 * 服务器能力位（#391 切片 2，取代原固定布尔字段集合 + 集中类型分支）。
 *
 * 能力集合 = 端口可选性派生能力 + 适配器声明的非端口派生能力；核心行为标志承载
 * 「提供出来的行为语义」（压缩是否异步、导出是否为归档、配置是否可写）。
 *
 * 消费方式：[ServerFeature] 成员查询——`ServerFeatures.TERMINAL in caps` 或
 * `caps.supports(ServerFeatures.TERMINAL)`；行为类读 [coreFlags]。
 * **不提供逐能力布尔 getter**：新增能力只加一个 [ServerFeature] id，不改本类
 * （用户故事 16/26：能力单一真相 + 由适配器提供而非领域集中分支）。
 */
data class ServerCapabilities(
    val coreFlags: CoreFlags,
    val features: Set<ServerFeature>,
) {
    operator fun contains(feature: ServerFeature): Boolean = feature in features

    fun supports(feature: ServerFeature): Boolean = feature in features
}
