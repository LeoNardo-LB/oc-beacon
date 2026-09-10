package dev.leonardo.ocbeacon.domain.model

/**
 * 核心行为标志（#391 ServerAdapter 架构）——**不可由端口可选性推导**的少量行为位。
 *
 * 设计约束（spec 能力模型）：这里只保留"推导不出来"的项，目标个位数。可由端口在场
 * 推导的能力一律走 `ServerPorts.derivedFeatures()`，不得在此重复登记。
 *
 * 判定口径：端口在场表达"客户端能否提供该能力"；本类表达"提供出来的行为语义"。
 */
data class CoreFlags(
    /** 压缩是否异步：HTTP 受理即回 + 终态由事件通告（V2 / DSH true；V1 false）。 */
    val compactionAsync: Boolean,

    /** 压缩是否与模型无关（DSH 走命令通道，不进模型）。 */
    val compactionModelIndependent: Boolean,

    /** 会话导出载荷是否为 ZIP 归档（DSH true → 落盘名 .zip；OpenCode false → .json）。 */
    val exportIsArchive: Boolean,

    /** 全局配置是否可写（V2 /api/config 只读）。 */
    val configEditable: Boolean,
)
