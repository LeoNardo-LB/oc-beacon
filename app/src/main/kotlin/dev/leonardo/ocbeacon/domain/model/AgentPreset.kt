package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * DSH Agent 预设（agentPreset.list 的 roster 条目）。
 *
 * 字段来自活体（ap-1）：value.presets 数组条目携带 id/trust/isDefault/name/description。
 * name/description 是服务端按 locale 解析后的展示原文（§6），客户端只读渲染不本地化；
 * id 是 agentPreset.select 的载荷值。
 */
@Serializable
data class AgentPreset(
    val id: String,
    val name: String,
    val description: String = "",
    val isDefault: Boolean = false,
)
