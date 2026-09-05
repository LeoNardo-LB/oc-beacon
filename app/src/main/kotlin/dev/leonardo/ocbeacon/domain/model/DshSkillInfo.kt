package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * #324④ DSH 会话技能（skills/list，契约钉死：{name, description, whenToUse?,
 * modelInvocable}）。
 *
 * 斜杠面板 skills 触发组数据源：modelInvocable 标识「模型可自主调用」
 * （区别于仅用户斜杠触发）；whenToUse 是触发时机说明（面板描述行）。
 */
@Serializable
data class DshSkillInfo(
    val name: String,
    val description: String = "",
    val whenToUse: String? = null,
    val modelInvocable: Boolean = false,
)
