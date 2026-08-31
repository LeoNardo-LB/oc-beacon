package dev.leonardo.ocbeacon.domain.model

/**
 * DSH 新会话默认 Agent 预设（settings.describe ns=agent-presets 的 default 投影）。
 *
 * [currentValue] 即当前默认预设 id（standard / code / minimal / cordis，档数由部署
 * 预设表决定）；[revision] 为 settings 文档修订号，写回 settings.mutate 时作为
 * expectedRevision 做乐观并发（陈旧 revision 会得到 settings-conflict）。
 */
data class DshAgentPresetDefault(
    val currentValue: String,
    val revision: Long = 0L,
)
