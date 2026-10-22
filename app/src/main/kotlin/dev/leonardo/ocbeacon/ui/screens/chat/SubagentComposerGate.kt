package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.SubagentCatalogEntry

/**
 * #310① 子会话 composer 门控（纯函数，单测 SubagentComposerGateTest）。
 *
 * - 主会话（parentSessionId == null）恒开——既有路径零语义变化；
 * - 仅子智能体能力位在场时子会话可续聊（mode=continuable 开；one-shot web 禁 composer 同款）；
 * - 无该能力的类型子会话维持既有只读镜像（恒关）；
 * - 加载中（mode == null）保守关——防 one-shot 误发；失败/目录无本行落
 *   [MODE_UNRESOLVED] 终态（#497 D4：与解析中区分，防骨架占位永久化）。
 */
internal object SubagentComposerGate {

    /**
     * mode 解析终态「不可建立续聊」（客户端状态，非目录条目词汇）：目录 RPC
     * 失败（0.2.x subagents/list 已移除→404）或目录无本行——此态下 app 无法
     * 续聊该子会话，事实只读。解析失败若停留 null 会让骨架占位永久化
     * （「加载中」永不兑现=误导），故以终态哨兵区分。
     */
    const val MODE_UNRESOLVED = "unresolved"

    fun composerVisible(parentSessionId: String?, subagentsSupported: Boolean, mode: String?): Boolean =
        parentSessionId == null ||
            (subagentsSupported && mode == SubagentCatalogEntry.MODE_CONTINUABLE)

    /**
     * #550 根修（2026-10-23）：interaction.error 对 composer 的隐藏条件收窄——
     * 仅在「无消息可看」（=ChatErrorState 兜底显示条件）时隐藏。断线期 REST
     * 失败置起的 error 无清除路径（重连成功不自愈）曾让 Room 缓存可看的会话
     * 永久失去输入栏（「消息能看没法回复」锁死——mock 域 soak HIT + 断线重进
     * 实验复现双证，journal 2026-10-23-550-root-cause-rca）。#310① 的「加载
     * 失败保守隐藏防误发」语义由 messagesEmpty 分支继续承载（真空转加载失败
     * 态时 ChatErrorState 占屏，composer 隐藏不冲突）。
     */
    fun composerVisibleOnError(error: String?, messagesEmpty: Boolean): Boolean =
        !(error != null && messagesEmpty)

    /**
     * 只读提示行：one-shot（明确不可续聊）或 unresolved（续聊无法建立）；
     * 加载中（null）不显示——防把在途会话误标只读。文案分流见调用点：
     * one-shot 用专用文案，unresolved 与能力位缺席共用只读镜像文案。
     *
     * #497 D3：无 subagents 能力位的服务器（V1/V2）子智能体会话恒只读镜像——同样给
     * 提示行（此前整块空白，连 DSH one-shot 的说明都没有）；mode 值仅在
     * 能力位在场时解析，此分支与 mode 无关。
     */
    fun readOnlyHintVisible(parentSessionId: String?, subagentsSupported: Boolean, mode: String?): Boolean =
        parentSessionId != null &&
            (
                !subagentsSupported ||
                    mode == SubagentCatalogEntry.MODE_ONE_SHOT ||
                    mode == MODE_UNRESOLVED
                )

    /**
     * #497 D4：DSH 子智能体会话 mode 异步解析期（mode=null）composer 骨架占位——
     * 防底部栏布局跳变 + 提前承接点击（挂载后 FocusRequester 接力聚焦，
     * 键盘不中断）。主会话恒关（composer 即时挂载）；能力位缺席（V1/V2）
     * 恒关（D3 提示行即时渲染，无解析期）。
     */
    fun composerPlaceholderVisible(parentSessionId: String?, subagentsSupported: Boolean, mode: String?): Boolean =
        parentSessionId != null &&
            subagentsSupported &&
            mode == null
}
