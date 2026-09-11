package dev.leonardo.ocbeacon.ui.extension

import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerFeature

/**
 * 条目级动作 / 入口所在表面（#391 切片9）。
 *
 * 与区域级 [dev.leonardo.ocbeacon.domain.model.ServerUiSlot] 同属统一贡献注册表：
 * 区域插槽承载"内容"，本枚举标识"条目动作 / 入口"挂载的具体表面。
 */
enum class ServerActionSurface {
    /** 会话内 FAB 工具栏入口（TODO/AGENT/GOAL/SHELL/QUEUE）。 */
    CHAT_FAB,
}

/**
 * 条目级动作 / 入口贡献契约（#391 切片9）。
 *
 * 每条贡献声明：表面 + 稳定 id + 所需能力 + 顺序；通用壳只做「按能力过滤 → 排序 →
 * 用统一组件渲染」，不接触服务器类型。能力位为 null 表示两面通用。
 */
interface ServerActionContribution {

    /** 挂载表面。 */
    val surface: ServerActionSurface

    /** 表面内稳定 id（通用壳据此映射到自身内容，如 FAB 入口枚举）。 */
    val actionId: String

    /** 所需能力位；null = 无能力要求（两面通用）。 */
    val requiredFeature: ServerFeature?

    /** 同表面内排序（小在前）。 */
    val order: Int

    /** 能力位驱动是否启用；默认按 [requiredFeature] 判定。 */
    fun isEnabled(caps: ServerCapabilities): Boolean {
        val required = requiredFeature ?: return true
        return required in caps
    }
}

/** 声明式条目动作贡献（数据形状即可，无需为每条建类）。 */
data class SimpleServerAction(
    override val surface: ServerActionSurface,
    override val actionId: String,
    override val requiredFeature: ServerFeature? = null,
    override val order: Int = 0,
) : ServerActionContribution
