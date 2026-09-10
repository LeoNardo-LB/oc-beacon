package dev.leonardo.ocbeacon.ui.extension

import androidx.compose.runtime.Composable
import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerUiSlot

/**
 * 界面插槽宿主上下文（#391 切片5，界面层）——通用屏幕向插槽贡献暴露的只读数据。
 *
 * 每个槽位有自己的宿主子类型（如 ProviderSettingsSlotHost）；贡献方在 Content 内
 * 做类型收窄，注册表在渲染前校验槽位与宿主匹配，不匹配即显式失败（不静默跳过）。
 */
interface ServerUiSlotHost

/**
 * 界面插槽贡献契约（#391 切片5）。
 *
 * 通用屏幕只做「按能力过滤 -> 排序 -> 用统一壳渲染」，不认识任何具体服务器类型；
 * 类型私有界面在自己包内实现本接口并以集合多绑定注册。
 *
 * 契约要点：
 * - [slot] 声明挂载的槽位（领域层纯枚举键）；
 * - [isEnabled] 只读能力位（不读服务器类型），默认启用；
 * - [Content] 只允许贡献内容（复用统一组件 / 令牌 / 动效），不得发明新交互模式；
 *   纵向滚动容器由宿主提供（禁止自行嵌套，见 DshCustomProvidersSection 事故注释）。
 */
interface ServerUiExtension {

    /** 挂载的界面插槽。 */
    val slot: ServerUiSlot

    /** 同槽位内排序（小在前）；用于稳定声明顺序。 */
    val order: Int get() = 0

    /** 能力位驱动是否启用；默认启用。 */
    fun isEnabled(caps: ServerCapabilities): Boolean = true

    /** 渲染该贡献；[host] 必须是本槽位对应的宿主子类型。 */
    @Composable
    fun Content(host: ServerUiSlotHost)
}

/** 提供商设置槽位宿主（#391）：把该槽位的最小上下文交给贡献方。 */
class ProviderSettingsSlotHost(
    val capabilities: ServerCapabilities,
) : ServerUiSlotHost

/** 服务器设置槽位宿主（#391 切片9）：无额外上下文，贡献方经 hiltViewModel 读取同作用域 VM。 */
class ServerSettingsSlotHost : ServerUiSlotHost

/** 会话列表头部槽位宿主（#391 切片9）：断连上下文 + 凭据输入出路回调。 */
class SessionListHeaderSlotHost(
    val tokenNeeded: Boolean,
    val onEnterToken: () -> Unit,
) : ServerUiSlotHost
