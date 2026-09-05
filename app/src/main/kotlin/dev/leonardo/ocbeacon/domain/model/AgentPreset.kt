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
    /** #324②：来源档（"system" 随部署 / "user" 用户可删）；缺省 system 容错。 */
    val trust: String = "system",
    /** #324②：损坏原因（roster broken 字段；预设文件不可解析时服务端给出）。 */
    val broken: String? = null,
)

/**
 * #324② agentPresets/list 完整 roster（presets + authorable——部署是否开放
 * 用户预设创作；authorable=false 时复制入口隐藏）。
 */
@Serializable
data class DshAgentPresetRoster(
    val presets: List<AgentPreset> = emptyList(),
    val authorable: Boolean = false,
)

/**
 * #324② agentPresets/read 文档（只读组成查看——content 是服务端 locale 解析
 * 后的预设文件原文，客户端只读渲染不编辑）。
 */
@Serializable
data class DshAgentPresetDocument(
    val agentPreset: String,
    val trust: String,
    val content: String,
    val name: String? = null,
    val description: String? = null,
)
