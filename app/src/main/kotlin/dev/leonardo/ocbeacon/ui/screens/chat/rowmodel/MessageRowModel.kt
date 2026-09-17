package dev.leonardo.ocbeacon.ui.screens.chat.rowmodel

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderableTurn
import dev.leonardo.ocbeacon.ui.screens.chat.tools.TurnLedgerSummary

/**
 * （2026-09-12 消息层扁平化）消息 / 通知「行模型」——本设计唯一新增 seam。
 *
 * 纯函数 / 纯数据：无 Compose、无网络、无 Android 依赖。
 * 信息架构的全部判定落在这里，UI 只做渲染：
 * - 字段分配（头部 / 尾部显示哪些）；
 * - 能力位门控（服务器缺失的整项隐藏，形态不变——docs/ui-conventions.md:5-21）；
 * - 状态徽标派生（只标进行中 / 已中断 / 出错，完成态不标）；
 * - 第 N 轮编号来源（服务器 turn 优先，否则客户端锚点序号）；
 * - 通知卡字段（同列同宽 / 跳转箭头 / 展开体 / 单行摘要）；
 * - 逐轮明细行字段。
 */

// ---------------------------------------------------------------------------
// 能力位
// ---------------------------------------------------------------------------

/**
 * 行模型能力位（spec 能力位门控表）：只产生「整项隐藏」差异，绝不改变形态。
 * 一律由 [ServerCapabilities] 能力位派生（#391 架构；不写服务器类型分支）。
 *
 * - cost：core.cost 在场（V1/V2 消息带 cost；DSH 全链无）→ 缺席即不渲染成本项；
 * - timing：core.turnTiming 在场（TTFT / tokens·s 仅 DSH 投影面）；
 * - feedback：core.feedback（👍👎 仅 DSH）；
 * - revert：core.session.revert（撤销仅 OpenCode 系）；
 * - fork：三面都有（fork 端点齐备）；
 * - messageDelete：core.message.delete（DSH 客户端恒返回 false → 不出现入口）。
 */
data class RowCapabilities(
    val cost: Boolean,
    val timing: Boolean,
    val feedback: Boolean,
    val revert: Boolean,
    val fork: Boolean,
    val messageDelete: Boolean,
) {
    companion object {
        /** 未探测 / 无服务器上下文时的最保守缺省（能力全隐藏；数据在场仍兜底）。 */
        val NONE = RowCapabilities(
            cost = false,
            timing = false,
            feedback = false,
            revert = false,
            fork = false,
            messageDelete = false,
        )
    }
}

fun rowCapabilitiesFor(caps: ServerCapabilities): RowCapabilities = RowCapabilities(
    cost = ServerFeatures.COST in caps,
    timing = ServerFeatures.TURN_TIMING in caps,
    feedback = ServerFeatures.FEEDBACK in caps,
    revert = ServerFeatures.SESSION_REVERT in caps,
    fork = true,
    messageDelete = ServerFeatures.MESSAGE_DELETE in caps,
)

// ---------------------------------------------------------------------------
// L1 消息层
// ---------------------------------------------------------------------------

/**
 * 头部状态徽标——只覆盖非完成态（US#6：完成态不显示，界面不被默认态噪音占满）。
 * 优先级：出错 > 已中断 > 流式中（终态错误优先于中断标记）。
 */
enum class MessageStatusBadge { STREAMING, INTERRUPTED, ERROR }

/**
 * 派生状态徽标。返回 null = 不标（已完成 / 无状态）。
 *
 * @param isStreaming turn 级流式判定（turn 内任一消息 completed == null）
 * @param finish 服务器终态（DSH "interrupted"；V2 "error" 等）
 * @param hasError 消息级错误对象在场
 */
fun statusBadgeFor(
    isStreaming: Boolean,
    finish: String?,
    hasError: Boolean,
): MessageStatusBadge? = when {
    hasError || finish == "error" -> MessageStatusBadge.ERROR
    finish == "interrupted" -> MessageStatusBadge.INTERRUPTED
    isStreaming -> MessageStatusBadge.STREAMING
    else -> null
}

/** 第 N 轮编号来源。 */
enum class TurnNumberSource { SERVER, CLIENT }

data class TurnNumber(val value: Int, val source: TurnNumberSource)

/**
 * 第 N 轮编号（US#28：会话内稳定且从 1 起）。
 *
 * - 服务器给（DSH data.turn）→ SERVER（会话内绝对、分页稳定）；
 * - 否则客户端按轮次锚点统计（V1/V2）→ CLIENT（窗口相对，分页会平移——
 *   如实降级，见 #310④ 裁决）。
 * 两者皆无 → null（不渲染序号，宁缺勿谎）。
 */
fun turnNumberFor(serverTurn: Long?, clientOrdinal: Int?): TurnNumber? {
    if (serverTurn != null && serverTurn > 0) {
        return TurnNumber(serverTurn.toInt(), TurnNumberSource.SERVER)
    }
    if (clientOrdinal != null && clientOrdinal > 0) {
        return TurnNumber(clientOrdinal, TurnNumberSource.CLIENT)
    }
    return null
}

/**
 * L1 尾部统计栏的字段分配结果。
 *
 * 「不放」token 桶 / TTFT / 速度 / 成本（进统计弹窗；US#7 只放步数·工具数摘要）。
 */
data class MessageRowTail(
    /** 模型名 + provider 图标（有则——用户第 1 轮裁决「模型名称放在底部」）。 */
    val modelId: String?,
    val providerId: String?,
    /** 步数 / 工具数摘要（US#7）。 */
    val stepCount: Int,
    val toolCallCount: Int,
    /** 轮次耗时（流式为实时 ticker，此值为流式起点；完成态为固定时长）。 */
    val durationMs: Long?,
    val streamingStartMs: Long?,
    /** 产出文件行（US#8，吸收原先挂在气泡外的产出行）。 */
    val producedFiles: List<String>,
    /** 台账摘要行（吸收气泡外的台账行）。 */
    val ledgerTurnNumber: TurnNumber?,
    val ledgerToolNames: List<String>,
    val ledgerTokensTotal: Long?,
    /** 常显动作。 */
    val copyAvailable: Boolean,
    val revertAvailable: Boolean,
    val jumpAvailable: Boolean,
    /** 「更多」弹窗是否有内容（无内容则不渲染入口）。 */
    val moreAvailable: Boolean,
    /** 尾部统计栏是否整体渲染（流式必有；否则有任一内容/动作）。 */
    val visible: Boolean,
)

/**
 * L1 尾部装配（纯函数）。
 *
 * @param turn 预计算的 RenderableTurn（可为 null——系统/通知类消息）
 * @param ledger 预计算的台账摘要（turnLedgerSummary 单源；含真实工具计数）
 * @param turnNumber 第 N 轮编号（server 优先）
 * @param userMessage 用户消息（决定 revert 动作；null = assistant/system）
 * @param caps 服务器能力位
 * @param isStreaming turn 级流式判定
 * @param hasCopy / [hasRevert] / [hasJump] 调用方实际提供了对应回调
 * @param hasFeedbackSheetItem / [hasDeleteSheetItem] / [hasMarkdownCopySheetItem]：「更多」条目在场
 */
fun messageRowTail(
    turn: RenderableTurn?,
    ledger: TurnLedgerSummary?,
    turnNumber: TurnNumber?,
    userMessage: Message.User?,
    caps: RowCapabilities,
    isStreaming: Boolean,
    hasCopy: Boolean,
    hasRevert: Boolean,
    hasJump: Boolean,
    hasFeedbackSheetItem: Boolean,
    hasDeleteSheetItem: Boolean,
    hasMarkdownCopySheetItem: Boolean,
    providerId: String? = null,
): MessageRowTail {
    val modelId = turn?.modelId?.takeIf { it.isNotBlank() }
    val moreAvailable = hasFeedbackSheetItem || hasDeleteSheetItem || hasMarkdownCopySheetItem
    val files = turn?.deliverableFiles ?: emptyList()
    val visible = isStreaming ||
        modelId != null ||
        (turn?.stepCount ?: 0) > 0 ||
        files.isNotEmpty() ||
        hasCopy || hasRevert || hasJump || moreAvailable
    return MessageRowTail(
        modelId = modelId,
        providerId = providerId?.takeIf { it.isNotBlank() },
        stepCount = turn?.stepCount ?: 0,
        toolCallCount = ledger?.toolCallCount ?: 0,
        durationMs = turn?.durationMs,
        streamingStartMs = if (isStreaming) turn?.turnStartMs else null,
        producedFiles = files,
        ledgerTurnNumber = turnNumber,
        ledgerToolNames = ledger?.toolNames ?: emptyList(),
        ledgerTokensTotal = ledger?.tokensTotal,
        copyAvailable = hasCopy,
        revertAvailable = hasRevert && userMessage != null && caps.revert,
        jumpAvailable = hasJump,
        moreAvailable = moreAvailable,
        visible = visible,
    )
}

// ---------------------------------------------------------------------------
// 逐轮明细（统计弹窗，US#25-27）
// ---------------------------------------------------------------------------

/**
 * 逐轮明细行（默认折叠 = 第 N 轮 · 耗时 · tokens 总量；展开 = 桶 / TTFT /
 * 速度 / 步骤数 / 成本 / 模型）。
 *
 * 能力位门控在装配期完成：capability 为 false 的字段恒 null、UI 整项不渲染。
 * 数据缺席（DSH 无 cost；V1/V2 无 TTFT / 速度）同样 null——「宁缺勿谎」。
 */
data class TurnDetailRow(
    val turnNumber: TurnNumber?,
    val durationMs: Long?,
    val tokensTotal: Long?,
    val tokensInput: Long?,
    val tokensOutput: Long?,
    val tokensReasoning: Long?,
    val tokensCacheRead: Long?,
    val tokensCacheWrite: Long?,
    /** DSH turn 级 TTFT（当前 app 无逐轮源 → null，整项隐藏，US#32）。 */
    val ttftMs: Long?,
    /** DSH turn 级 tokens·s（同上，无源即隐藏）。 */
    val tokensPerSecond: Double?,
    val stepCount: Int,
    val toolCallCount: Int,
    /** 成本（OpenCode；DSH 恒 null → 整项不渲染，US#31）。 */
    val cost: Double?,
    val modelId: String?,
    val providerId: String?,
    /** 行是否可展开（有任一明细字段才给展开入口）。 */
    val expandable: Boolean,
)

/** 逐轮装配输入（与 RenderableTurn 解耦，便于单测直接构造）。 */
data class TurnDetailInput(
    val serverTurn: Long?,
    val clientOrdinal: Int?,
    val durationMs: Long?,
    val tokens: Message.Assistant.Tokens?,
    val stepCount: Int,
    val toolCallCount: Int,
    val cost: Double?,
    val modelId: String?,
    val providerId: String?,
    val ttftMs: Long? = null,
    val tokensPerSecond: Double? = null,
)

/**
 * 逐轮明细装配（纯函数，US#25-27）。
 *
 * - 第 N 轮：服务器 turn 优先（DSH），否则客户端锚点序号（V1/V2）；
 * - 保持输入顺序（UI 传 newest-first 输入即得倒序列表）；
 * - 运行时缺数据：V1/V2 无 TTFT / 速度、DSH 无 cost → 对应字段 null，UI 整项隐藏；
 * - 可选桶（reasoning / cache）为 0 时归一为 null（不渲染空项，US#31 语义）。
 */
fun buildTurnDetailRows(
    inputs: List<TurnDetailInput>,
    caps: RowCapabilities,
): List<TurnDetailRow> = inputs.map { i ->
    val t = i.tokens
    val modelId = i.modelId?.takeIf { it.isNotBlank() }
    val providerId = i.providerId?.takeIf { it.isNotBlank() }
    val cost = if (caps.cost) i.cost else null
    val ttftMs = if (caps.timing) i.ttftMs else null
    val tps = if (caps.timing) i.tokensPerSecond else null
    val reasoning = t?.reasoning?.takeIf { it > 0 }?.toLong()
    val cacheRead = t?.cache?.read?.takeIf { it > 0 }?.toLong()
    val cacheWrite = t?.cache?.write?.takeIf { it > 0 }?.toLong()
    val total = t?.total?.toLong() ?: t?.let { (it.input + it.output).toLong() }
    val expandable = modelId != null || cost != null || ttftMs != null || tps != null ||
        reasoning != null || cacheRead != null || cacheWrite != null
    TurnDetailRow(
        turnNumber = turnNumberFor(i.serverTurn, i.clientOrdinal),
        durationMs = i.durationMs,
        tokensTotal = total,
        tokensInput = t?.input?.toLong()?.takeIf { it > 0 },
        tokensOutput = t?.output?.toLong()?.takeIf { it > 0 },
        tokensReasoning = reasoning,
        tokensCacheRead = cacheRead,
        tokensCacheWrite = cacheWrite,
        ttftMs = ttftMs,
        tokensPerSecond = tps,
        stepCount = i.stepCount,
        toolCallCount = i.toolCallCount,
        cost = cost,
        modelId = modelId,
        providerId = providerId,
        expandable = expandable,
    )
}

// ---------------------------------------------------------------------------
// L2 通知层
// ---------------------------------------------------------------------------

/**
 * 通知卡行模型（L2 统一通知卡家族——EventCard scaffold 的薄适配器输出）。
 *
 * US#17-22：折叠态一行读懂（图标 + 类型 + 来源 + 单行摘要）；本体点击 =
 * 展开 / 收起（唯一入口）；跳转 = 尾部弱化外链箭头钮（常驻，仅可跳转者）；
 * 与消息同列同宽、不缩进（US#32 裁决：缩进会与卡片层展开体内缩混淆）。
 */
data class NotificationRowModel(
    val label: String,
    val failed: Boolean,
    /** 折叠态单行摘要（必须自解释）；null = 无描述行。 */
    val description: String?,
    /** 展开体是否在场（决定 chevron 与本体可点）。 */
    val hasBody: Boolean,
    /** 跳转子会话目标 id；null = 不渲染箭头（本体点击只做展开，避免一次点击两含义）。 */
    val navTargetId: String?,
) {
    /** 本体点击是否可展开 / 收起（唯一入口）。 */
    val bodyClickable: Boolean get() = hasBody
    /** 尾部跳转箭头是否常驻渲染。 */
    val navArrowVisible: Boolean get() = navTargetId != null
    /** 与消息同列同宽、不缩进（恒真，供 UI 断言）。 */
    val sameWidthNoIndent: Boolean get() = true
}

/** 通知卡行模型装配（纯函数）。 */
fun notificationRowModel(
    label: String,
    failed: Boolean,
    description: String?,
    hasBody: Boolean,
    navTargetId: String?,
): NotificationRowModel = NotificationRowModel(
    label = label,
    failed = failed,
    description = description,
    hasBody = hasBody,
    navTargetId = navTargetId,
)

// ---------------------------------------------------------------------------
// 事件身份键（去重：撤销 UI 层 ×N，改装配层按身份键收敛——US#22）
// ---------------------------------------------------------------------------

/**
 * 通知 / 事件的稳定身份键：工具 callId → 子会话 id → 消息 id。
 * 同键 = 同源事件（重复注入），装配层保留一条并原位更新状态。
 */
fun <T> dedupeByEventIdentity(items: List<T>, identityKey: (T) -> String?): List<T> {
    val indexByKey = HashMap<String, Int>()
    val out = ArrayList<T>(items.size)
    for (item in items) {
        val key = identityKey(item)
        if (key == null) {
            out.add(item)
            continue
        }
        val existing = indexByKey[key]
        if (existing != null) {
            // 原位更新：保留首个出现位置，值取最新一条（重复注入不刷屏，状态最新）。
            out[existing] = item
        } else {
            indexByKey[key] = out.size
            out.add(item)
        }
    }
    return out
}

fun eventIdentityKey(
    callId: String?,
    childSessionId: String?,
    messageId: String,
): String = when {
    !callId.isNullOrBlank() -> "call:" + callId
    !childSessionId.isNullOrBlank() -> "child:" + childSessionId
    else -> "msg:" + messageId
}
